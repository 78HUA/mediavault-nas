package top.itning.yunshunas.video.mq;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import top.itning.yunshunas.video.video.VideoTransformHandler;

import java.io.IOException;

/**
 * 转码任务消费者（可选）
 * <p>
 * 仅在 <code>nas.mq.enabled=true</code> 时生效。手动 ack：
 * <ul>
 *     <li>转码成功、或任务已被本机/其它实例处理过（幂等）→ ack；</li>
 *     <li>真的转码失败 → nack 且不重回队列，交给死信队列 ——
 *         直接重投会让失败消息立刻被再次消费，形成死循环。</li>
 * </ul>
 *
 * @author itning
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nas.mq", name = "enabled", havingValue = "true")
public class TranscodeConsumer {

    private final VideoTransformHandler videoTransformHandler;

    @Autowired
    public TranscodeConsumer(VideoTransformHandler videoTransformHandler) {
        this.videoTransformHandler = videoTransformHandler;
    }

    @RabbitListener(queues = TranscodeMqConfig.TRANSCODE_QUEUE,
            containerFactory = "transcodeListenerContainerFactory")
    public void onTranscodeTask(String location, Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        long start = System.currentTimeMillis();
        try {
            boolean noRetryNeeded = videoTransformHandler.transcodeIfNeeded(location);
            if (noRetryNeeded) {
                channel.basicAck(deliveryTag, false);
                if (log.isDebugEnabled()) {
                    log.debug("转码任务处理完成 file={} 耗时={}ms", location, System.currentTimeMillis() - start);
                }
            } else {
                log.warn("转码失败，转入死信队列：{}", location);
                channel.basicNack(deliveryTag, false, false);
            }
        } catch (Exception e) {
            log.error("消费转码任务异常，转入死信队列：{}", location, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
