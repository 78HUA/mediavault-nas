package top.itning.yunshunas.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 子进程执行器
 * <p>
 * 与原先直接使用 {@link ProcessBuilder} 相比，统一处理了四件事：
 * <ol>
 *     <li><b>超时</b>：子进程挂死时强制回收，不会永久占住调用线程；</li>
 *     <li><b>退出码</b>：把退出码返回给调用方，避免「命令失败但看起来成功」；</li>
 *     <li><b>stdin</b>：立即关闭，避免子进程等待标准输入而挂住；</li>
 *     <li><b>中断</b>：调用线程被中断时回收子进程并复位中断标志，不吞中断。</li>
 * </ol>
 *
 * @author itning
 */
public final class ProcessRunner {

    /**
     * 默认超时。这是防止子进程**挂死**的兜底，不是「任务最长耗时」的约束：
     * 取值远大于任何单文件转码的正常耗时，确有需要可由调用方显式传入。
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofHours(2);

    /**
     * 保留在结果里的最大输出行数。ffmpeg 会持续输出进度行，无上限保留会撑爆内存。
     * 注意：截断只作用于**结果集**，逐行回调仍然会收到每一行。
     */
    private static final int MAX_RETAINED_LINES = 2000;

    private static final Charset CHARSET = Charset.defaultCharset();

    private ProcessRunner() {
        throw new UnsupportedOperationException();
    }

    /**
     * 进程执行结果
     *
     * @param exitCode 退出码；超时强杀时为 -1
     * @param timedOut 是否因超时被强制回收
     * @param output   子进程的输出行（合并了 stderr，最多保留 {@value #MAX_RETAINED_LINES} 行）
     */
    public record Result(int exitCode, boolean timedOut, List<String> output) {

        /**
         * @return 未超时且退出码为 0
         */
        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }

        /**
         * 失败时的可读描述，用于异常信息
         *
         * @return 描述文本
         */
        public String describeFailure() {
            if (timedOut) {
                return "进程超时被强制回收";
            }
            return "进程退出码 " + exitCode;
        }
    }

    /**
     * 已启动进程的句柄。
     * <p>
     * 供 aria2c 这类常驻守护进程使用：调用方需要拿到进程引用才能在停机时主动回收它，
     * 否则应用退出后子进程会变成孤儿进程继续占用端口。
     */
    public static final class Handle {

        private final Process process;
        private final Thread reader;
        private final List<String> output;

        private Handle(Process process, Thread reader, List<String> output) {
            this.process = process;
            this.reader = reader;
            this.output = output;
        }

        /**
         * @return 进程引用
         */
        public Process process() {
            return process;
        }

        /**
         * @return 目前收集到的输出行
         */
        public List<String> output() {
            return List.copyOf(output);
        }

        /**
         * 停止进程并等待收尾：先给优雅退出的机会，超时未退再强制回收。
         *
         * @param grace 优雅退出的等待时间
         * @return 是否在宽限期内正常退出
         */
        public boolean stop(Duration grace) {
            if (process.isAlive()) {
                process.destroy();
                boolean exited = false;
                try {
                    exited = process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!exited) {
                    process.destroyForcibly();
                    try {
                        process.waitFor();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                joinQuietly(reader);
                return exited;
            }
            joinQuietly(reader);
            return true;
        }
    }

    /**
     * 启动命令后立即返回句柄，不等待进程结束
     *
     * @param command      命令
     * @param lineConsumer 每行输出的回调，可为 <code>null</code>
     * @return 进程句柄
     * @throws IOException IOException
     */
    public static Handle start(List<String> command, Consumer<String> lineConsumer) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        // 子进程若等待标准输入会一直挂住，直接给它 EOF
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // 关闭 stdin 失败不影响后续读取与回收
        }
        List<String> retained = Collections.synchronizedList(new ArrayList<>());
        Thread reader = Thread.ofVirtual().name("process-reader").start(
                () -> readOutput(process, retained, lineConsumer));
        return new Handle(process, reader, retained);
    }

    /**
     * 执行命令，使用默认超时
     *
     * @param command      命令
     * @param lineConsumer 每行输出的回调，可为 <code>null</code>
     * @return 执行结果
     * @throws IOException IOException
     */
    public static Result run(List<String> command, Consumer<String> lineConsumer) throws IOException {
        return run(command, DEFAULT_TIMEOUT, lineConsumer);
    }

    /**
     * 执行命令，不消费输出
     *
     * @param command 命令
     * @return 执行结果
     * @throws IOException IOException
     */
    public static Result run(List<String> command) throws IOException {
        return run(command, DEFAULT_TIMEOUT, null);
    }

    /**
     * 执行命令
     *
     * @param command      命令
     * @param timeout      超时；为 <code>null</code> 表示不设超时
     * @param lineConsumer 每行输出的回调，可为 <code>null</code>
     * @return 执行结果
     * @throws IOException IOException
     */
    public static Result run(List<String> command, Duration timeout, Consumer<String> lineConsumer) throws IOException {
        Handle handle = start(command, lineConsumer);
        Process process = handle.process();
        boolean timedOut = false;
        try {
            boolean finished;
            if (timeout == null) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (!finished) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (InterruptedException e) {
            // 不能吞中断：回收子进程后复位中断标志，交给上层决定是否退出
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("执行命令时被中断：" + command, e);
        } finally {
            joinQuietly(handle.reader);
        }
        return new Result(timedOut ? -1 : process.exitValue(), timedOut, handle.output());
    }

    private static void readOutput(Process process, List<String> retained, Consumer<String> lineConsumer) {
        try (InputStream inputStream = process.getInputStream();
             InputStreamReader isr = new InputStreamReader(inputStream, CHARSET);
             BufferedReader br = new BufferedReader(isr)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (lineConsumer != null) {
                    lineConsumer.accept(line);
                }
                if (retained.size() < MAX_RETAINED_LINES) {
                    retained.add(line);
                }
            }
        } catch (IOException e) {
            // 进程被强杀时流会异常关闭，属于预期情况
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
