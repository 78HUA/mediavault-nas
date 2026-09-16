package top.itning.yunshunas.video.video;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.itning.yunshunas.common.lock.RedisDistributedLock;
import top.itning.yunshunas.common.socket.ProgressWebSocket;
import top.itning.yunshunas.video.mq.TranscodeMqConfig;
import top.itning.yunshunas.video.repository.IVideoRepository;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author itning
 * @since 2019/7/14 16:41
 */
@Component
public class VideoTransformHandler {
    private static final Logger logger = LoggerFactory.getLogger(VideoTransformHandler.class);

    /**
     * 待转码任务上限。队列必须有界：无界队列会让最大线程数彻底失效、任务无限堆积直到 OOM，
     * 且无法形成背压（阿里开发手册明确禁止 Executors 默认的无界队列写法）。
     */
    private static final int PENDING_CAPACITY = 64;

    /**
     * 优雅停机时等待在途任务结束的时间
     */
    private static final long SHUTDOWN_WAIT_SECONDS = 30L;

    /**
     * 进度推送的最小间隔（毫秒）。ffmpeg 每秒会输出多行状态，逐条广播就是消息风暴，
     * 而且每条都要写给所有会话；限制间隔后消息量与客户端数量都不再敏感。
     */
    private static final long PROGRESS_PUSH_INTERVAL_MS = 500L;

    /**
     * 转码分布式锁的键前缀
     */
    private static final String TRANSCODE_LOCK_PREFIX = "yunshu:transcode:lock:";

    /**
     * 转码分布式锁的存活时间。
     * <p>
     * 正常结束或失败都会在 finally 中显式释放，只有进程被强杀时才会残留，最长这么久后自动过期。
     * 取值明显大于单个文件转码的正常耗时，避免「还在转却被误判为锁已过期」；
     * 反过来，若真有文件转码超过这个时长，锁会在中途过期并可能被另一实例重复转 ——
     * 这种情况下正确的做法是加锁续期（看门狗），本项目未实现，此处如实记录。
     */
    private static final Duration TRANSCODE_LOCK_TTL = Duration.ofMinutes(30);

    /**
     * 提交结果
     */
    public enum SubmitResult {
        /**
         * 已加入待转码队列
         */
        SUBMITTED,
        /**
         * 同一文件正在转码或已在队列中，本次忽略
         */
        ALREADY_IN_PROGRESS,
        /**
         * 转码产物已存在，无需重复转码
         */
        ALREADY_DONE,
        /**
         * 队列已满，拒绝本次提交
         */
        REJECTED
    }

    private final Video2M3u8Helper video2M3u8Helper;
    private final IVideoRepository iVideoRepository;
    private final RedisDistributedLock redisDistributedLock;
    private final RabbitTemplate rabbitTemplate;
    /**
     * 是否把任务投递到 MQ 而不是进程内队列（由 nas.mq.enabled 控制，默认关闭）
     */
    private final boolean mqEnabled;
    private final ThreadPoolExecutor transformExecutorService;
    private final Video2M3u8Helper.Progress progress;
    /**
     * 在途任务集合（正在转码 + 已在队列）。用集合做原子去重，
     * 替代原先「查 Map → 遍历队列 → 入队」的三步非原子检查，同时消除 O(n) 的队列遍历。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong submittedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    /**
     * 上次进度推送时间，用于节流；多个转码任务共用一个窗口，等于给总推送速率设了上限
     */
    private final AtomicLong lastProgressPushAt = new AtomicLong();

    public VideoTransformHandler(Video2M3u8Helper video2M3u8Helper, IVideoRepository iVideoRepository,
                                 RedisDistributedLock redisDistributedLock, RabbitTemplate rabbitTemplate,
                                 @Value("${nas.mq.enabled:false}") boolean mqEnabled) {
        this.video2M3u8Helper = video2M3u8Helper;
        this.iVideoRepository = iVideoRepository;
        this.redisDistributedLock = redisDistributedLock;
        this.rabbitTemplate = rabbitTemplate;
        this.mqEnabled = mqEnabled;
        int processors = Runtime.getRuntime().availableProcessors();
        // 转码的实际计算力来自 ffmpeg 子进程，且 ffmpeg 自身就是多线程的。
        // 外层再按核数并发会让线程总量远超核数（实测 12 并发比串行慢约 9 倍），故取核数一半。
        int concurrency = Math.max(1, processors / 2);
        this.transformExecutorService = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(PENDING_CAPACITY),
                new ThreadFactoryBuilder().setNameFormat("trans-pool-%d").build(),
                new ThreadPoolExecutor.AbortPolicy());
        logger.info("转码线程池初始化：并发 {}，待处理上限 {}（CPU 核数 {}）",
                concurrency, PENDING_CAPACITY, processors);
        progress = new Video2M3u8Helper.Progress() {
            @Override
            public void onLine(String line) {
                // 不再逐行广播：ffmpeg 每秒会输出多行状态，逐行推送对客户端和转码线程双向加压。
                // 进度统一走 onProgress 的节流通道，原始日志另有 /log 端点。
            }

            @Override
            public void onFinish(String fromFile, String toPath, String fileName) {
                ProgressWebSocket.sendMessage(String.format("完成转换 文件：%s 目标路径：%s 文件名：%s", fromFile, toPath, fileName));
            }

            @Override
            public void onError(Exception e, String fromFile, String toPath, String fileName) {
                ProgressWebSocket.sendMessage(String.format("Exception In Video Convert: %s %s %s", fromFile, toPath, fileName));
                ProgressWebSocket.sendMessage(e.getMessage());
            }

            @Override
            public void onProgress(long frame, long totalFrames, String percentage, String line) {
                sendProgressThrottled(String.format("%d/%d %s", frame, totalFrames, percentage));
            }
        };
    }

    /**
     * 节流后的进度推送
     * <p>
     * 一个转码任务会连续产出进度，而每条都要写给所有会话；
     * 这里限制为「距上次推送超过 {@value #PROGRESS_PUSH_INTERVAL_MS} 毫秒」才发一条，
     * 使推送速率不再随客户端数量线性增长。
     *
     * @param message 消息
     */
    private void sendProgressThrottled(String message) {
        long now = System.currentTimeMillis();
        long last = lastProgressPushAt.get();
        if (now - last < PROGRESS_PUSH_INTERVAL_MS) {
            return;
        }
        if (lastProgressPushAt.compareAndSet(last, now)) {
            ProgressWebSocket.sendMessage(message);
        }
    }

    /**
     * 提交转码任务
     *
     * @param location 视频文件路径
     * @return 提交结果
     */
    public SubmitResult submit(String location) {
        String writeDir = iVideoRepository.getWriteDir(location);
        String locationMd5 = iVideoRepository.getLocationMd5(location);
        File m3u8File = new File(writeDir + File.separator + locationMd5 + ".m3u8");
        if (m3u8File.exists()) {
            return SubmitResult.ALREADY_DONE;
        }
        if (mqEnabled) {
            return publishTranscodeTask(location);
        }
        // 本机原子去重：add 返回 false 说明本机已在途（正在转码或已在队列）
        if (!inFlight.add(location)) {
            return SubmitResult.ALREADY_IN_PROGRESS;
        }
        // 跨实例去重：加分布式锁。Redis 未启用或不可用时会放行，由上面的本机去重兜底
        Optional<RedisDistributedLock.LockToken> lock = redisDistributedLock.tryLock(
                TRANSCODE_LOCK_PREFIX + locationMd5, TRANSCODE_LOCK_TTL);
        if (lock.isEmpty()) {
            inFlight.remove(location);
            logger.info("该文件正在被其它实例转码，忽略本次提交：{}", location);
            return SubmitResult.ALREADY_IN_PROGRESS;
        }
        RedisDistributedLock.LockToken lockToken = lock.get();
        try {
            transformExecutorService.execute(() -> {
                try {
                    transcode(location, writeDir, locationMd5);
                } finally {
                    redisDistributedLock.unlock(lockToken);
                    inFlight.remove(location);
                }
            });
        } catch (RejectedExecutionException e) {
            redisDistributedLock.unlock(lockToken);
            inFlight.remove(location);
            long rejected = rejectedCount.incrementAndGet();
            logger.warn("转码队列已满，拒绝任务：{}（累计拒绝 {} 个）", location, rejected);
            return SubmitResult.REJECTED;
        }
        submittedCount.incrementAndGet();
        return SubmitResult.SUBMITTED;
    }

    /**
     * 供 MQ 消费者调用：执行一次转码
     * <p>
     * 与「提交」分开，是因为启用 MQ 后任务先落到 RabbitMQ，真正干活的可能是**另一个实例**的消费者；
     * 此时才应该抢分布式锁 —— 生产者并不持有锁。
     *
     * @param location 视频文件路径
     * @return <code>true</code> 表示无需重试（转好了、产物已存在、或别的实例正在/已经处理）；
     *         <code>false</code> 表示本次确实转码失败
     */
    public boolean transcodeIfNeeded(String location) {
        String writeDir = iVideoRepository.getWriteDir(location);
        String locationMd5 = iVideoRepository.getLocationMd5(location);
        if (new File(writeDir + File.separator + locationMd5 + ".m3u8").exists()) {
            // 幂等：消息可能被重复投递，产物已存在就直接跳过
            logger.info("转码产物已存在，跳过：{}", location);
            return true;
        }
        if (!inFlight.add(location)) {
            logger.info("本机已在转码，跳过：{}", location);
            return true;
        }
        Optional<RedisDistributedLock.LockToken> lock = redisDistributedLock.tryLock(
                TRANSCODE_LOCK_PREFIX + locationMd5, TRANSCODE_LOCK_TTL);
        if (lock.isEmpty()) {
            inFlight.remove(location);
            logger.info("该文件正在被其它实例转码，跳过：{}", location);
            return true;
        }
        try {
            return transcode(location, writeDir, locationMd5);
        } finally {
            redisDistributedLock.unlock(lock.get());
            inFlight.remove(location);
        }
    }

    /**
     * 投递任务到 RabbitMQ
     *
     * @param location 视频文件路径
     * @return 提交结果
     */
    private SubmitResult publishTranscodeTask(String location) {
        try {
            // 队列持久化 + 消息默认持久化，重启不丢；投递失败则让调用方退避重试
            rabbitTemplate.convertAndSend(TranscodeMqConfig.TRANSCODE_QUEUE, location);
            submittedCount.incrementAndGet();
            logger.info("转码任务已投递到 MQ：{}", location);
            return SubmitResult.SUBMITTED;
        } catch (Exception e) {
            logger.error("投递转码任务到 MQ 失败：{}", location, e);
            return SubmitResult.REJECTED;
        }
    }

    /**
     * 真正执行一次转码，并统计失败
     *
     * @param location     视频文件路径
     * @param writeDir     输出目录
     * @param locationMd5  路径 MD5
     * @return 是否成功
     */
    private boolean transcode(String location, String writeDir, String locationMd5) {
        if (video2M3u8Helper.videoConvert(location, writeDir, locationMd5, progress)) {
            return true;
        }
        long failed = failedCount.incrementAndGet();
        logger.warn("转码失败 file={}（累计失败 {}）", location, failed);
        return false;
    }

    /**
     * 线程池与队列状态
     *
     * @return 状态快照
     */
    public Map<String, Object> status() {
        Map<String, Object> statusMap = new HashMap<>(10);
        statusMap.put("activeCount", transformExecutorService.getActiveCount());
        statusMap.put("completedTaskCount", transformExecutorService.getCompletedTaskCount());
        statusMap.put("corePoolSize", transformExecutorService.getCorePoolSize());
        statusMap.put("maxPoolSize", transformExecutorService.getMaximumPoolSize());
        statusMap.put("poolSize", transformExecutorService.getPoolSize());
        statusMap.put("taskCount", transformExecutorService.getTaskCount());
        statusMap.put("queueSize", transformExecutorService.getQueue().size());
        statusMap.put("queueCapacity", PENDING_CAPACITY);
        statusMap.put("inFlightCount", inFlight.size());
        statusMap.put("submittedCount", submittedCount.get());
        statusMap.put("rejectedCount", rejectedCount.get());
        statusMap.put("failedCount", failedCount.get());
        return statusMap;
    }

    /**
     * 优雅停机：先不再接受新任务，等待在途任务结束；超时才强制中断。
     * 否则停机时队列里的任务被静默丢弃、正在跑的 ffmpeg 被硬中断并留下半截产物。
     */
    @PreDestroy
    public void shutdown() {
        int pending = transformExecutorService.getQueue().size();
        logger.info("开始停止转码线程池，待处理任务数：{}", pending);
        transformExecutorService.shutdown();
        try {
            if (!transformExecutorService.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("转码线程池未在 {} 秒内结束，强制停止", SHUTDOWN_WAIT_SECONDS);
                transformExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            transformExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("转码线程池已停止");
    }
}
