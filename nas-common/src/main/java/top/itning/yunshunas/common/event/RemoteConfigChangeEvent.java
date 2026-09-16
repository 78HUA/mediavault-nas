package top.itning.yunshunas.common.event;

import org.springframework.context.ApplicationEvent;

/**
 * 来自其它实例的配置变更事件
 * <p>
 * 与 {@link ConfigChangeEvent} 的区别：后者表示「本机配置发生了变化」，
 * 由本机的设置接口触发；本事件表示「收到了别的实例广播过来的配置变更」，
 * 需要在本机落库并触发同样的本地重建流程。
 *
 * @author 78HUA
 */
public class RemoteConfigChangeEvent extends ApplicationEvent {

    public RemoteConfigChangeEvent(String rawMessage) {
        super(rawMessage);
    }

    /**
     * @return 广播过来的原始消息
     */
    public String getRawMessage() {
        return String.valueOf(getSource());
    }
}
