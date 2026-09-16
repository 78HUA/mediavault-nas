package top.itning.yunshunas.video.video;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.itning.yunshunas.common.socket.ProgressWebSocket;
import top.itning.yunshunas.video.repository.IVideoRepository;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
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

    public VideoTransformHandler(Video2M3u8Helper video2M3u8Helper, IVideoRepository iVideoRepository) {
        this.video2M3u8Helper = video2M3u8Helper;
        this.iVideoRepository = iVideoRepository;
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
        // 原子去重：add 返回 false 说明已在途（正在转码或已在队列）
        if (!inFlight.add(location)) {
            return SubmitResult.ALREADY_IN_PROGRESS;
        }
        try {
            transformExecutorService.execute(() -> {
                try {
                    if (!video2M3u8Helper.videoConvert(location, writeDir, locationMd5, progress)) {
                        long failed = failedCount.incrementAndGet();
                        logger.warn("转码失败 file={}（累计失败 {}）", location, failed);
                    }
                } finally {
                    inFlight.remove(location);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(location);
            long rejected = rejectedCount.incrementAndGet();
            logger.warn("转码队列已满，拒绝任务：{}（累计拒绝 {} 个）", location, rejected);
            return SubmitResult.REJECTED;
        }
        submittedCount.incrementAndGet();
        return SubmitResult.SUBMITTED;
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
