package top.itning.yunshunas.video.video;

import com.jayway.jsonpath.Filter;
import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONArray;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.DigestUtils;
import top.itning.yunshunas.common.util.Tuple2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.jayway.jsonpath.Criteria.where;
import static com.jayway.jsonpath.Filter.filter;
import static top.itning.yunshunas.common.util.CommandUtils.process;
import static top.itning.yunshunas.common.util.CommandUtils.processChecked;

/**
 * ffmpeg 4.1.3 版本测试通过
 *
 * @author itning
 * @since 2019/7/13 23:12
 */
public class Video2M3u8Helper {
    private static final Logger logger = LoggerFactory.getLogger(Video2M3u8Helper.class);

    /**
     * 视频编码
     */
    private static final String VIDEO_H_264 = "Video: h264";
    /**
     * 音频编码
     */
    private static final String AUDIO_AAC = "Audio: aac";
    /**
     * 进度起始字符串
     */
    private static final String START_FRAME_STR = "frame=";
    /**
     * 进度结束字符串
     */
    private static final String END_FRAME_STR = "fps";
    /**
     * 视频分割时间
     */
    private static final String SPLIT_TIME_SECOND = "10";


    private final String ffmpegLocation;
    private final String ffprobeLocation;

    public Video2M3u8Helper(String ffmpegBinDir) {
        this.ffmpegLocation = ffmpegBinDir + File.separator + "ffmpeg";
        this.ffprobeLocation = ffmpegBinDir + File.separator + "ffprobe";
    }

    /**
     * 进度条
     */
    public interface Progress {
        /**
         * 视频准备开始转码时回调
         *
         * @param fromFile 源文件
         * @param toPath   目标路径
         * @param fileName 文件名（不要扩展名）
         */
        default void onStart(String fromFile, String toPath, String fileName) {

        }

        /**
         * 日志
         *
         * @param line 每条日志
         */
        default void onLine(String line) {
        }

        /**
         * 视频转换完成
         *
         * @param fromFile 源文件
         * @param toPath   目标路径
         * @param fileName 文件名（不要扩展名）
         */
        default void onFinish(String fromFile, String toPath, String fileName) {
        }

        /**
         * 视频转换错误时
         *
         * @param e        出现的异常
         * @param fromFile 源文件
         * @param toPath   目标路径
         * @param fileName 文件名（不要扩展名）
         */
        default void onError(Exception e, String fromFile, String toPath, String fileName) {
        }

        /**
         * 转换进度
         *
         * @param frame       当前帧数
         * @param totalFrames 总帧数
         * @param percentage  百分比
         * @param line        原始消息
         */
        default void onProgress(long frame, long totalFrames, String percentage, String line) {
        }
    }

    /**
     * 视频文件转码成符合HLS规范的视频文件
     *
     * @param fromFile  源文件
     * @param toPath    目标文件夹
     * @param copyVideo 是否直接复制，不进行视频转码
     * @param copyAudio 是否直接复制，不进行音频转码
     * @return 转码完成的文件路径
     * @throws IOException IOException
     */
    private String videoStandardization(String fromFile, String toPath, boolean copyVideo, boolean copyAudio,
                                        Progress progress) throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("3/4. start copy {} {} {} {}", fromFile, toPath, copyAudio, copyAudio);
        }
        final long videoFrames = getVideoFrames(fromFile);
        String randomFileName = DigestUtils.md5DigestAsHex(fromFile.getBytes()) + ".mp4";
        List<String> command = new ArrayList<>(8);
        command.add(ffmpegLocation);
        command.add("-i");
        command.add(fromFile);
        command.add("-vcodec");
        if (copyVideo) {
            command.add("copy");
        } else {
            command.add("h264");
        }
        command.add("-acodec");
        if (copyAudio) {
            command.add("copy");
        } else {
            command.add("aac");
        }
        command.add(toPath + File.separator + randomFileName);

        processChecked(command, line -> {
            if (progress != null) {
                progress.onLine(line);
                reportProgress(line, videoFrames, progress);
            }
        });
        if (logger.isDebugEnabled()) {
            logger.debug("4/4. end copy {} {} {} {}", fromFile, toPath, copyAudio, copyAudio);
        }
        return command.get(command.size() - 1);
    }

    /**
     * 转换视频文件为M3U8
     *
     * @param fromFile 源文件
     * @param toPath   目标路径
     * @param fileName 文件名（不要扩展名）
     * @param progress 进度条
     * @return 产物是否成功生成
     */
    public boolean videoConvert(final String fromFile, final String toPath, final String fileName, Progress progress) {
        if (logger.isDebugEnabled()) {
            logger.debug("start videoConvert {} {} {}", fromFile, toPath, fileName);
        }
        try {
            if (progress != null) {
                progress.onStart(fromFile, toPath, fileName);
            }
            Tuple2<Boolean, Boolean> compliance = checkComplianceWithSpecificationsForHls(fromFile);
            if (logger.isDebugEnabled()) {
                logger.debug("video: {} audio: {}", compliance.t1(), compliance.t2());
            }
            String copy = videoStandardization(fromFile, toPath, compliance.t1(), compliance.t2(), progress);
            // 构建命令
            List<String> command = new ArrayList<>(16);
            command.add(ffmpegLocation);
            command.add("-i");
            command.add(copy);
            command.add("-codec");
            command.add("copy");
            // -vbsf 已被 ffmpeg 移除（5.0 起），需使用 -bsf:v
            command.add("-bsf:v");
            command.add("h264_mp4toannexb");
            command.add("-map");
            command.add("0");
            command.add("-f");
            command.add("segment");
            command.add("-segment_list");
            command.add(toPath + File.separator + fileName + ".m3u8");
            command.add("-segment_time");
            command.add(SPLIT_TIME_SECOND);
            command.add(toPath + File.separator + fileName + "-%03d.ts");

            processChecked(command, line -> {
                if (progress != null) {
                    progress.onLine(line);
                }
            });

            File m3u8File = new File(toPath + File.separator + fileName + ".m3u8");
            if (!m3u8File.isFile()) {
                // 退出码为 0 却没产出产物，同样按失败处理：
                // 否则「m3u8 文件存在即已完成」的判据会把残缺结果当成成品，且此后无法重转
                throw new IllegalStateException("转码结束但未生成 m3u8 文件：" + m3u8File.getPath());
            }
            boolean delete = new File(toPath + File.separator + DigestUtils.md5DigestAsHex(fromFile.getBytes()) + ".mp4").delete();
            if (logger.isDebugEnabled()) {
                logger.debug("delete fromFile copy file {}", delete);
                logger.debug("end videoConvert {} {} {}", fromFile, toPath, fileName);
            }
            if (progress != null) {
                progress.onFinish(fromFile, toPath, fileName);
            }
            return true;
        } catch (Exception e) {
            // 原先只落 debug 日志且不带堆栈，转码失败在默认日志级别下完全不可见
            logger.error("视频转码失败 file={} toPath={} fileName={}", fromFile, toPath, fileName, e);
            // 清掉残缺产物：ffmpeg 的 segment 封装器是边转边写 .m3u8 的，
            // 中途失败会留下「文件存在但内容不全」的 m3u8，
            // 而「m3u8 存在即已完成」的判据会把它当成成品，导致该文件永远无法重转。
            cleanupPartialArtifacts(toPath, fileName, fromFile);
            if (progress != null) {
                progress.onError(e, fromFile, toPath, fileName);
            }
            return false;
        }
    }

    /**
     * 清理一次转码留下的残缺产物：m3u8、分片 ts，以及标准化阶段的中间 mp4
     *
     * @param toPath   目标目录
     * @param fileName 文件名（不要扩展名）
     * @param fromFile 源文件
     */
    private void cleanupPartialArtifacts(String toPath, String fileName, String fromFile) {
        File[] files = new File(toPath).listFiles();
        if (files == null) {
            return;
        }
        String tsPrefix = fileName + "-";
        String intermediateMp4 = DigestUtils.md5DigestAsHex(fromFile.getBytes()) + ".mp4";
        for (File file : files) {
            String name = file.getName();
            boolean isPartial = name.equals(fileName + ".m3u8")
                    || (name.startsWith(tsPrefix) && name.endsWith(".ts"))
                    || name.equals(intermediateMp4);
            if (isPartial && !file.delete()) {
                logger.warn("清理转码残留文件失败：{}", file.getPath());
            }
        }
    }

    /**
     * 转换视频文件为M3U8
     *
     * @param fromFile 源文件
     * @param toPath   目标路径
     * @param fileName 文件名（不要扩展名）
     * @return 产物是否成功生成
     */
    public boolean videoConvert(String fromFile, String toPath, String fileName) {
        return videoConvert(fromFile, toPath, fileName, null);
    }

    /**
     * 检查视频文件是否符合HLS规范
     *
     * @param wantCheckVideoFile 想要检查的视频文件
     * @return 1.视频符合？ 2.音频符合？
     * @throws IOException IOException
     */
    private Tuple2<Boolean, Boolean> checkComplianceWithSpecificationsForHls(String wantCheckVideoFile) throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("1/4. start checkComplianceWithSpecificationsForHls {}", wantCheckVideoFile);
        }
        List<String> command = new ArrayList<>(3);
        command.add(ffmpegLocation);
        command.add("-i");
        command.add(wantCheckVideoFile);
        StringBuilder stringBuilder = new StringBuilder();
        process(command, stringBuilder::append);
        String s = stringBuilder.toString();
        boolean video = s.contains(VIDEO_H_264);
        boolean audio = s.contains(AUDIO_AAC);
        if (logger.isDebugEnabled()) {
            logger.debug("2/4. end checkComplianceWithSpecificationsForHls {}", wantCheckVideoFile);
        }
        //音视频都是HLS规范
        if (video && audio) {
            return new Tuple2<>(true, true);
        }
        //视频不符合HLS规范，音频符合
        else if (!video && audio) {
            return new Tuple2<>(false, true);
        }
        //音频不符合HLS规范，视频符合
        else if (video) {
            return new Tuple2<>(true, false);
        } else {
            return new Tuple2<>(false, false);
        }
    }

    /**
     * 上报进度
     *
     * @param line        输出的信息
     * @param totalFrames 视频总共帧数
     * @param progress    进度回调。由调用方传入而不是存到实例字段：
     *                    本类是单例，多个转码任务并发时字段会被互相覆盖，
     *                    导致 A 文件的进度推到 B 文件的回调上
     */
    private void reportProgress(String line, final long totalFrames, Progress progress) {
        if (progress == null) {
            return;
        }
        int endIndex = startWithFrame(line) ? line.indexOf(END_FRAME_STR) : -1;
        if (totalFrames != -1 && endIndex > START_FRAME_STR.length()) {
            long frame = NumberUtils.toLong(line.substring(START_FRAME_STR.length(), endIndex).trim(), -1);
            // String.format 线程安全，替代原先非线程安全的静态 DecimalFormat。
            // 注意 DecimalFormat 的 "0.00%" 模式会自动乘 100，这里必须显式乘，否则百分比会缩小 100 倍
            String percentage = String.format(Locale.ROOT, "%.2f%%", frame * 100.0 / (double) totalFrames);
            progress.onProgress(frame, totalFrames, percentage, line);
        } else {
            progress.onProgress(-1, totalFrames, null, line);
        }
    }

    /**
     * 是否是帧数进度行。
     * <p>
     * 原实现直接用 line.indexOf("fps") 取下标再 substring，一旦该行以 frame= 开头
     * 却不含 fps 字段（下标为 -1），就会抛 StringIndexOutOfBoundsException；
     * 而异常会一路抛到 videoConvert 的 catch，把一次本来成功的转码判成失败。
     *
     * @param line 输出的信息
     * @return 是进度行返回 <code>true</code>
     */
    private boolean startWithFrame(String line) {
        return line.startsWith(START_FRAME_STR) && line.indexOf(END_FRAME_STR) > START_FRAME_STR.length();
    }

    /**
     * 获取视频帧数
     *
     * @param videoFile 视频文件
     * @return 帧数（字符串转长整形失败会返回-1）
     * @throws IOException IOException
     */
    private long getVideoFrames(String videoFile) throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("start getVideoFrames");
        }
        List<String> command = new ArrayList<>(8);
        command.add(ffprobeLocation);
        command.add("-v");
        command.add("quiet");
        command.add("-print_format");
        command.add("json");
        command.add("-show_format");
        command.add("-show_streams");
        command.add(videoFile);
        StringBuilder stringBuilder = new StringBuilder();
        process(command, stringBuilder::append);
        String s = stringBuilder.toString();

        Filter videoFilter = filter(where("codec_type").is("video"));
        JSONArray read = JsonPath.read(s, "$.streams[?].nb_frames", videoFilter);
        if (logger.isDebugEnabled()) {
            logger.debug("end getVideoFrames");
        }
        if (read.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("json array is empty");
            }
            return -1;
        } else {
            return NumberUtils.toLong(read.get(0).toString(), -1);
        }
    }
}
