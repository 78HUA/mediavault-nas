package top.itning.yunshunas.common.util;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * 命令行工具类
 *
 * @author itning
 * @since 2019/7/17 20:56
 */
public class CommandUtils {
    private CommandUtils() {
        throw new UnsupportedOperationException();
    }

    /**
     * 执行命令（带默认超时）
     *
     * @param command     命令
     * @param commandInfo 输出信息
     * @throws IOException IOException
     */
    public static void process(List<String> command, Consumer<String> commandInfo) throws IOException {
        ProcessRunner.run(command, commandInfo);
    }

    /**
     * 执行长驻命令，不设超时
     * <p>
     * 供 aria2c 这类设计上就要一直运行的守护进程使用：给它套超时会在到点后被强制回收。
     *
     * @param command     命令
     * @param commandInfo 输出信息
     * @throws IOException IOException
     */
    public static void processWithoutTimeout(List<String> command, Consumer<String> commandInfo) throws IOException {
        ProcessRunner.run(command, null, commandInfo);
    }

    /**
     * 执行命令并返回输出
     *
     * @param command 命令
     * @return 输出
     * @throws IOException IOException
     */
    public static String process(List<String> command) throws IOException {
        ProcessRunner.Result result = ProcessRunner.run(command);
        // 保持历史行为：各行直接相连、不插入分隔符。
        // 调用方用 NumberUtils.toLong 解析 ffprobe 的单值输出，
        // 若改成按行拼接会多出换行符，Long.parseLong 失败会静默返回 0。
        return String.join("", result.output());
    }
}
