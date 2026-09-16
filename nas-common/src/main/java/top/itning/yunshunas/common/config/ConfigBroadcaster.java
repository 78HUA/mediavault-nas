package top.itning.yunshunas.common.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.itning.yunshunas.common.db.ApplicationConfig;
import top.itning.yunshunas.common.event.ConfigChangeEvent;
import top.itning.yunshunas.common.event.RemoteConfigChangeEvent;
import top.itning.yunshunas.common.util.JsonUtils;

import java.util.Objects;
import java.util.UUID;

/**
 * 配置变更广播
 * <p>
 * 项目里原本已有 {@link ConfigChangeEvent} + {@code ApplicationListener} 的机制
 * （FtpConfig / ElasticsearchConfig / DataSourceConfig 都是监听它来重建自身），
 * 但这个机制只在**本机**生效：在实例 A 上改配置，实例 B 不会知道。
 * 这里用 Redis 的发布/订阅把这半边补上 —— 属于「做完作者没做完的那一半」，而不是硬塞中间件。
 * <p>
 * 两个方向：
 * <ul>
 *     <li>本机配置变了 → 广播出去（{@link #onLocalChange}）；</li>
 *     <li>收到别的实例的变更 → 在本机落库并触发同样的重建（{@link #onRemoteChange}）。</li>
 * </ul>
 * 未启用 Redis 时两个方向都不做事，退化为原先的单机行为。
 *
 * @author itning
 */
@Slf4j
@Component
public class ConfigBroadcaster {

    private final ApplicationConfig applicationConfig;
    private final NasRedisConfig nasRedisConfig;

    /**
     * 本实例标识：用于忽略自己发出的消息
     */
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 正在应用远端变更时置位。
     * 应用远端变更走的是本机的 setSetting，它又会触发一次 ConfigChangeEvent，
     * 若不置位就会再次广播出去，在实例之间来回弹。
     */
    private final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Autowired
    public ConfigBroadcaster(ApplicationConfig applicationConfig, NasRedisConfig nasRedisConfig) {
        this.applicationConfig = applicationConfig;
        this.nasRedisConfig = nasRedisConfig;
    }

    /**
     * 本机配置发生变化 → 广播给其它实例
     *
     * @param event 配置变更事件
     */
    @EventListener
    public void onLocalChange(ConfigChangeEvent event) {
        if (Boolean.TRUE.equals(applyingRemote.get()) || !nasRedisConfig.enabled()) {
            return;
        }
        Object config = event.getSource();
        if (Objects.isNull(config)) {
            return;
        }
        try {
            Message message = new Message(instanceId, config.getClass().getName(),
                    JsonUtils.OBJECT_MAPPER.writeValueAsString(config));
            nasRedisConfig.getRedisTemplate().convertAndSend(NasRedisConfig.CONFIG_CHANNEL,
                    JsonUtils.OBJECT_MAPPER.writeValueAsString(message));
            log.info("已广播配置变更：{}", config.getClass().getSimpleName());
        } catch (Exception e) {
            // 广播失败不影响本机已生效的配置，只降级为单机行为
            log.warn("广播配置变更失败，本次仅本机生效：{}", e.getMessage());
        }
    }

    /**
     * 收到其它实例广播的配置变更 → 在本机落库（会顺带触发本机组件重建）
     *
     * @param event 远端配置变更事件
     */
    @EventListener
    public void onRemoteChange(RemoteConfigChangeEvent event) {
        Message message;
        try {
            message = JsonUtils.OBJECT_MAPPER.readValue(event.getRawMessage(), Message.class);
        } catch (Exception e) {
            log.warn("解析远端配置变更失败：{}", e.getMessage());
            return;
        }
        if (instanceId.equals(message.instanceId())) {
            return;
        }
        try {
            Class<?> configClass = Class.forName(message.className());
            Object config = JsonUtils.OBJECT_MAPPER.readValue(message.body(), configClass);
            applyingRemote.set(Boolean.TRUE);
            try {
                applyConfig(config);
            } finally {
                applyingRemote.set(Boolean.FALSE);
            }
            log.info("已应用来自实例 {} 的配置变更：{}", message.instanceId(), configClass.getSimpleName());
        } catch (Exception e) {
            log.warn("应用远端配置变更失败：{}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void applyConfig(Object config) {
        // setSetting 会落库、失效缓存并发布本机的 ConfigChangeEvent，因此各组件会照常重建
        applicationConfig.setSetting((T) config);
    }

    /**
     * @return 本实例标识
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 广播报文
     *
     * @param instanceId 发送方实例标识
     * @param className  配置类的全限定名
     * @param body       配置内容的 JSON
     */
    record Message(@JsonProperty("instanceId") String instanceId,
                   @JsonProperty("className") String className,
                   @JsonProperty("body") String body) {
    }
}
