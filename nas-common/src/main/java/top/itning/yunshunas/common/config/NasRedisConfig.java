package top.itning.yunshunas.common.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.StringUtils;
import top.itning.yunshunas.common.db.ApplicationConfig;
import top.itning.yunshunas.common.event.ConfigChangeEvent;
import top.itning.yunshunas.common.event.RemoteConfigChangeEvent;

import java.nio.charset.StandardCharsets;
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
 * <p>
 * <b>降级的边界（实测得出，与直觉相反）：</b>只靠 Lettuce 的懒连接并不够。
 * {@code RedisMessageListenerContainer.start()} 会**同步**取一次连接，
 * Redis 不可达时它直接抛 {@code RedisConnectionFailureException}；
 * 这个异常若穿出 {@code @PostConstruct}，Spring 会判定该 bean 创建失败并取消整个刷新，
 * 结果是 —— 应用根本起不来，而不是"Redis 功能不可用"。所以订阅的启动必须单独兜住。
 *
 * @author 78HUA
 */
@Slf4j
@Configuration
public class NasRedisConfig implements ApplicationListener<ConfigChangeEvent> {

    /**
     * 配置变更广播频道
     */
    public static final String CONFIG_CHANNEL = "yunshu:config:change";

    private final ApplicationConfig applicationConfig;
    private final ApplicationEventPublisher applicationEventPublisher;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisMessageListenerContainer listenerContainer;

    @Autowired
    public NasRedisConfig(ApplicationConfig applicationConfig, ApplicationEventPublisher applicationEventPublisher) {
        this.applicationConfig = applicationConfig;
        this.applicationEventPublisher = applicationEventPublisher;
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
        // 无论自检是否通过都尝试启动订阅：Redis 可达时正常订阅上；
        // 不可达时 start() 会同步抛异常，但**绝不能让它穿出 @PostConstruct** ——
        // 那会让整个 Spring 容器取消启动，应用连首页都打不开。
        // 代价是：订阅失败后本实例不再接收配置广播，Redis 恢复也不会自动补上（需重启）；
        // 分布式锁不受影响，它是每次调用即时判断、失败即放行。
        try {
            startConfigSubscription();
        } catch (Exception e) {
            log.warn("订阅配置变更频道失败，本实例不启用配置广播（Redis 恢复后需重启才能补上）：{}", e.getMessage());
            stopListenerContainer();
        }
    }

    /**
     * 订阅配置变更频道
     * <p>
     * 本类只负责把收到的消息转成本机的 {@link RemoteConfigChangeEvent}，
     * 具体解析、落库与组件重建交给 ConfigBroadcaster —— 保持职责单一。
     */
    private void startConfigSubscription() {
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.addMessageListener(this::onConfigMessage, new ChannelTopic(CONFIG_CHANNEL));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        log.info("已订阅配置变更频道：{}", CONFIG_CHANNEL);
    }

    private void onConfigMessage(Message message, byte[] pattern) {
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        applicationEventPublisher.publishEvent(new RemoteConfigChangeEvent(raw));
    }

    @PreDestroy
    public void destroy() {
        stopListenerContainer();
        if (Objects.nonNull(connectionFactory)) {
            connectionFactory.destroy();
        }
        connectionFactory = null;
        redisTemplate = null;
    }

    /**
     * 停掉订阅容器并清掉引用
     * <p>
     * 既用于正常停机，也用于订阅启动失败后的现场清理 ——
     * 半初始化的容器仍握着连接工厂，留着只会掩盖问题。
     */
    private void stopListenerContainer() {
        if (Objects.isNull(listenerContainer)) {
            return;
        }
        try {
            listenerContainer.stop();
            listenerContainer.destroy();
        } catch (Exception e) {
            // DisposableBean#destroy 声明了 throws Exception；停机阶段的清理失败不应影响关闭流程
            log.warn("停止配置变更订阅失败：{}", e.getMessage());
        }
        listenerContainer = null;
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
