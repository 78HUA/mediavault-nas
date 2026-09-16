package top.itning.yunshunas.video.controller;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
        String writeDir = iVideoRepository.getWriteDir(location);
        FileUtils.deleteDirectory(new File(writeDir));
        File file = new File(location);
        if (!file.exists()) {
            throw new RuntimeException("文件不存在");
        }
        if (!file.delete()) {
            throw new RuntimeException("文件删除失败");
        }
    }

    @PostMapping("/delTranscoding")
    @ResponseBody
    public void delTranscodingFile(@RequestParam String location) throws IOException {
        String writeDir = iVideoRepository.getWriteDir(location);
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
        boolean accepted = videoTransformHandler.put(path);

        Map<String, Object> result = new LinkedHashMap<>(4);
        result.put("location", path);
        result.put("locationMd5", locationMd5);
        result.put("accepted", accepted);
        result.put("m3u8Url", "/hls/" + locationMd5 + ".m3u8");
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
