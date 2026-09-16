package top.itning.yunshunas.video.mq;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 转码队列的 MQ 配置（可选）
 * <p>
 * 由 <code>nas.mq.enabled=true</code> 打开。**默认关闭时这个类整体不生效** ——
 * 不声明队列、不建立连接、不启动消费者，转码仍走进程内的有界队列 + 线程池，
 * 与引入 MQ 之前完全一致。这是刻意的：不能因为引入中间件而破坏「零配置也能启动」。
 * <p>
 * 队列拓扑：
 * <pre>
 *   生产者 ──► yunshu.transcode.queue ──► 消费者
 *                     │ 处理失败（reject 且不重回队列）
 *                     ▼
 *             yunshu.transcode.dlx ──► yunshu.transcode.dlq
 * </pre>
 *
 * @author itning
 */
@Configuration
@ConditionalOnProperty(prefix = "nas.mq", name = "enabled", havingValue = "true")
public class TranscodeMqConfig {

    /**
     * 转码任务队列
     */
    public static final String TRANSCODE_QUEUE = "yunshu.transcode.queue";

    /**
     * 死信交换机
     */
    public static final String TRANSCODE_DLX = "yunshu.transcode.dlx";

    /**
     * 死信队列：消费失败的任务落在这里，便于人工排查与重放
     */
    public static final String TRANSCODE_DLQ = "yunshu.transcode.dlq";

    /**
     * 死信路由键
     */
    public static final String DEAD_ROUTING_KEY = "transcode.dead";

    /**
     * 消费并发数。与原先线程池的并发保持一致（CPU 核数一半）——
     * ffmpeg 自身多线程，外层再按核数并发会让线程总量远超核数。
     */
    private static final int CONSUMER_CONCURRENCY = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    @Bean
    public Declarables transcodeQueueDeclarables() {
        Queue deadQueue = QueueBuilder.durable(TRANSCODE_DLQ).build();
        DirectExchange deadLetterExchange = new DirectExchange(TRANSCODE_DLX, true, false);
        // 主队列：持久化 + 指定死信路由
        Queue transcodeQueue = QueueBuilder.durable(TRANSCODE_QUEUE)
                .deadLetterExchange(TRANSCODE_DLX)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
        return new Declarables(List.of(
                transcodeQueue,
                deadQueue,
                deadLetterExchange,
                BindingBuilder.bind(deadQueue).to(deadLetterExchange).with(DEAD_ROUTING_KEY)
        ));
    }

    @Bean
    public SimpleRabbitListenerContainerFactory transcodeListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(CONSUMER_CONCURRENCY);
        factory.setPrefetchCount(1);
        // 处理抛异常时不重回队列，直接走死信 —— 否则失败消息会立刻被重新投递并死循环重试
        factory.setDefaultRequeueRejected(false);
        // 手动 ack：转码是长任务，处理期间不能因为消费者断开而重复投递
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        return factory;
    }
}
