package top.itning.yunshunas.common.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Redis 配置
 * <p>
 * 与项目里的 Elasticsearch 配置同一套模式：存在内嵌 SQLite 的 setting 表里，
 * 通过设置接口读写，未配置时不启用任何 Redis 能力。
 *
 * @author 78HUA
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NasRedisProperties {

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 主机
     */
    private String host = "127.0.0.1";

    /**
     * 端口
     */
    private int port = 6379;

    /**
     * 密码
     */
    private String password;

    /**
     * 数据库序号
     */
    private int database;
}
