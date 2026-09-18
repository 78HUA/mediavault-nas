package top.itning.yunshunas.controller;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import top.itning.yunshunas.common.config.NasFtpProperties;
import top.itning.yunshunas.common.config.NasProperties;
import top.itning.yunshunas.common.config.NasRedisProperties;
import top.itning.yunshunas.common.db.ApplicationConfig;
import top.itning.yunshunas.common.model.RestModel;
import top.itning.yunshunas.common.util.JsonUtils;
import top.itning.yunshunas.music.config.ElasticsearchConfig;
import top.itning.yunshunas.music.config.ElasticsearchProperties;
import top.itning.yunshunas.music.config.NasMusicProperties;

/**
 * @author itning
 * @since 2023/4/7 13:41
 */
@Validated
@RequestMapping("/api/setting")
@RestController
public class SettingController {
    private final ApplicationConfig applicationConfig;
    private final ElasticsearchConfig elasticsearchConfig;

    @Autowired
    public SettingController(ApplicationConfig applicationConfig, ElasticsearchConfig elasticsearchConfig) {
        this.applicationConfig = applicationConfig;
        this.elasticsearchConfig = elasticsearchConfig;
    }

    @GetMapping("/{type}")
    public ResponseEntity<RestModel<Object>> getSetting(@PathVariable String type) {
        switch (type) {
            case "nas" -> {
                return RestModel.ok(applicationConfig.getSetting(NasProperties.class));
            }
            case "datasource" -> {
                return RestModel.ok(applicationConfig.getSetting(NasMusicProperties.class));
            }
            case "ftp" -> {
                return RestModel.ok(applicationConfig.getSetting(NasFtpProperties.class));
            }
            case "es" -> {
                return RestModel.ok(applicationConfig.getSetting(ElasticsearchProperties.class));
            }
            case "redis" -> {
                return RestModel.ok(applicationConfig.getSetting(NasRedisProperties.class));
            }
            default -> throw new IllegalArgumentException("未知类型");
        }
    }

    @PostMapping("/{type}")
    public ResponseEntity<RestModel<Object>> setSetting(@PathVariable String type, @RequestBody String value) throws Exception {
        switch (type) {
            case "nas" -> {
                NasProperties nasProperties = JsonUtils.OBJECT_MAPPER.readValue(value, NasProperties.class);
                return RestModel.ok(applicationConfig.setSetting(nasProperties));
            }
            case "datasource" -> {
                NasMusicProperties nasMusicProperties = JsonUtils.OBJECT_MAPPER.readValue(value, NasMusicProperties.class);
                return RestModel.ok(applicationConfig.setSetting(nasMusicProperties));
            }
            case "ftp" -> {
                NasFtpProperties nasFtpProperties = JsonUtils.OBJECT_MAPPER.readValue(value, NasFtpProperties.class);
                return RestModel.ok(applicationConfig.setSetting(nasFtpProperties));
            }
            case "es" -> {
                ElasticsearchProperties elasticsearchProperties = JsonUtils.OBJECT_MAPPER.readValue(value, ElasticsearchProperties.class);
                applicationConfig.setSetting(elasticsearchProperties);
                // 配置一定存下来了，但「能不能连上」是另一件事 —— 连不上时 ElasticsearchConfig 会降级。
                // 原实现让连接异常直接冒出去变成 500：用户以为没保存成功，其实已经落库，
                // 下次启动反而因为这条配置起不来。这里改成如实回报，不用异常表达业务结果。
                String lastError = elasticsearchConfig.getLastError();
                if (elasticsearchProperties.isEnabled() && StringUtils.isNotBlank(lastError)) {
                    RestModel<Object> body = new RestModel<>();
                    body.setCode(HttpStatus.OK.value());
                    body.setMsg("配置已保存，但当前连接 Elasticsearch 失败，本次降级为不启用：" + lastError);
                    body.setData(applicationConfig.getSetting(ElasticsearchProperties.class));
                    return ResponseEntity.ok(body);
                }
                return RestModel.ok(applicationConfig.getSetting(ElasticsearchProperties.class));
            }
            case "redis" -> {
                NasRedisProperties nasRedisProperties = JsonUtils.OBJECT_MAPPER.readValue(value, NasRedisProperties.class);
                return RestModel.ok(applicationConfig.setSetting(nasRedisProperties));
            }
            default -> throw new IllegalArgumentException("未知类型");
        }
    }
}
