package top.itning.yunshunas.common.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import top.itning.yunshunas.common.db.ApplicationConfig;
import top.itning.yunshunas.common.event.ConfigChangeEvent;

import java.util.Objects;

/**
 * Redis 配置
 * <p>
 * 刻意做成**可降级**：没有配置 Redis 时这里什么都不做，应用照常启动；
 * 用到 Redis 的能力各自先判断 {@link #enabled()} 再决定是否跳过。
 * 这样引入中间件不会破坏项目原有的「零配置也能启动」特性。
 * <p>
 * 连接工厂与模板是手工创建的，不走容器生命周期，因此必须自己触发
 * <code>afterPropertiesSet()</code> 与 <code>destroy()</code>。
 * Lettuce 是懒连接，所以 Redis 没启动也不会导致应用启动失败，只会在真正使用时报错。
 *
 * @author itning
 */
@Slf4j
@Configuration
public class NasRedisConfig implements ApplicationListener<ConfigChangeEvent> {

    private final ApplicationConfig applicationConfig;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @Autowired
    public NasRedisConfig(ApplicationConfig applicationConfig) {
        this.applicationConfig = applicationConfig;
    }

    @PostConstruct
    public void init() {
        NasRedisProperties properties = applicationConfig.getSetting(NasRedisProperties.class);
        if (Objects.isNull(properties) || !properties.isEnabled()) {
            return;
        }
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        configuration.setDatabase(properties.getDatabase());
        if (StringUtils.hasText(properties.getPassword())) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        log.info("Redis 已启用：{}:{} db{}", properties.getHost(), properties.getPort(), properties.getDatabase());
        // 主动探活一次：Lettuce 是懒连接，不探的话要等到真正用 Redis 时才发现配置写错了。
        // 注意这里失败只告警不抛异常 —— 配置错误不应把整个应用拖垮。
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            log.info("Redis 连接自检通过：{}", connection.ping());
        } catch (Exception e) {
            log.warn("Redis 连接自检失败，配置已保存但当前不可用：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (Objects.nonNull(connectionFactory)) {
            connectionFactory.destroy();
        }
        connectionFactory = null;
        redisTemplate = null;
    }

    @Override
    public void onApplicationEvent(ConfigChangeEvent event) {
        if (event.getSource() instanceof NasRedisProperties) {
            this.destroy();
            this.init();
        }
    }

    /**
     * @return 是否已启用 Redis
     */
    public boolean enabled() {
        return Objects.nonNull(redisTemplate);
    }

    /**
     * @return Redis 模板
     */
    public StringRedisTemplate getRedisTemplate() {
        if (Objects.isNull(redisTemplate)) {
            throw new IllegalStateException("Redis 未配置，请先配置！");
        }
        return redisTemplate;
    }
}
