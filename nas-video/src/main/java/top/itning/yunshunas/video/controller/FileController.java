package top.itning.yunshunas.video.controller;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import top.itning.yunshunas.common.model.RestModel;
import top.itning.yunshunas.video.entity.FileEntity;
import top.itning.yunshunas.video.entity.Link;
import top.itning.yunshunas.video.repository.IVideoRepository;
import top.itning.yunshunas.video.service.VideoService;
import top.itning.yunshunas.video.video.VideoTransformHandler;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author itning
 * @since 2019/7/14 18:48
 */
@Controller("videoFileController")
public class FileController {
    private final VideoService videoService;
    private final IVideoRepository iVideoRepository;
    private final VideoTransformHandler videoTransformHandler;

    @Autowired
    public FileController(VideoService videoService, IVideoRepository iVideoRepository,
                          VideoTransformHandler videoTransformHandler) {
        this.videoService = videoService;
        this.iVideoRepository = iVideoRepository;
        this.videoTransformHandler = videoTransformHandler;
    }

    @GetMapping("/location")
    @ResponseBody
    public ResponseEntity<RestModel<List<FileEntity>>> location(@RequestParam String path) throws UnsupportedEncodingException {
        return RestModel.ok(videoService.getFileEntities(path));
    }

    @GetMapping("/links")
    @ResponseBody
    public ResponseEntity<RestModel<List<Link>>> links(String path) throws UnsupportedEncodingException {
        return RestModel.ok(Link.build(path));
    }

    @PostMapping("/del")
    @ResponseBody
    public void delFile(@RequestParam String location) throws IOException {
        // 先校验、再动手：原实现拿到 location 就按原样绝对路径删除，
        // 且 getWriteDir() 还会先创建目录 —— 任何能访问该端口的人都能删掉机器上的任意文件。
        File file = new File(location);
        if (!file.exists()) {
            throw new IllegalArgumentException("文件不存在：" + location);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("不是文件，拒绝删除：" + location);
        }
        if (!videoService.isVideoFile(file.getName())) {
            throw new IllegalArgumentException("不是支持的视频格式，拒绝删除：" + file.getName());
        }
        String writeDir = iVideoRepository.getWriteDir(file.getPath());
        FileUtils.deleteDirectory(new File(writeDir));
        if (!file.delete()) {
            throw new RuntimeException("文件删除失败：" + location);
        }
    }

    @PostMapping("/delTranscoding")
    @ResponseBody
    public void delTranscodingFile(@RequestParam String location) throws IOException {
        // 这里只清理转码产物目录，不要求源文件还在；但仍限制只对视频文件生效，
        // 否则可以用任意字符串在该目录下反复创建并删除子目录
        File file = new File(location);
        if (!videoService.isVideoFile(file.getName())) {
            throw new IllegalArgumentException("不是支持的视频格式：" + file.getName());
        }
        String writeDir = iVideoRepository.getWriteDir(file.getPath());
        FileUtils.deleteDirectory(new File(writeDir));
    }

    /**
     * 请求把视频转码为HLS（m3u8 + ts 分片）
     * <p>
     * 转码在后台异步进行，本接口只负责提交任务并立即返回，不等待转码完成。
     * 转码进度通过 WebSocket 端点 <code>/p</code> 推送，
     * 转码产物可通过返回的 <code>m3u8Url</code> 访问。
     *
     * @param location 视频文件路径
     * @return 提交结果，含转码产物地址
     */
    @PostMapping("/transcode")
    @ResponseBody
    public ResponseEntity<RestModel<Map<String, Object>>> transcode(@RequestParam String location) {
        File file = new File(location).getAbsoluteFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在：" + location);
        }
        if (!videoService.isVideoFile(file.getName())) {
            throw new IllegalArgumentException("不是支持的视频格式：" + file.getName());
        }
        String path = file.getPath();
        String locationMd5 = iVideoRepository.getLocationMd5(path);
        VideoTransformHandler.SubmitResult submitResult = videoTransformHandler.submit(path);

        Map<String, Object> result = new LinkedHashMap<>(5);
        result.put("location", path);
        result.put("locationMd5", locationMd5);
        result.put("result", submitResult.name());
        result.put("accepted", submitResult == VideoTransformHandler.SubmitResult.SUBMITTED);
        result.put("m3u8Url", "/hls/" + locationMd5 + ".m3u8");

        if (submitResult == VideoTransformHandler.SubmitResult.REJECTED) {
            // 队列已满属于背压场景，用 429 让调用方退避重试，而不是无声地排队等待
            RestModel<Map<String, Object>> body = new RestModel<>();
            body.setCode(HttpStatus.TOO_MANY_REQUESTS.value());
            body.setMsg("转码队列已满，请稍后重试");
            body.setData(result);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }
        return RestModel.ok(result);
    }

    /**
     * 查询转码队列与线程池状态
     *
     * @return 线程池与待转码队列快照
     */
    @GetMapping("/transcode/status")
    @ResponseBody
    public ResponseEntity<RestModel<Map<String, Object>>> transcodeStatus() {
        return RestModel.ok(videoTransformHandler.status());
    }
}
