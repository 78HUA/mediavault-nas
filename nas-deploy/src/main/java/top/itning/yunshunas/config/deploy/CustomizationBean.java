package top.itning.yunshunas.config.deploy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 容器配置
 * <p>
 * 原实现是 Undertow 专用写法（WebServerFactoryCustomizer&lt;UndertowServletWebServerFactory&gt;，
 * 用于设置 Undertow 的 DefaultByteBufferPool）。但 Spring Boot 4.x 已不再支持 Undertow
 * （4.1.1 的 BOM 里只管理 tomcat / jetty / netty，该 starter 在中央仓库的版本止于 4.0.0-M1），
 * 实际运行的是 Tomcat —— 于是那个定制器从未被调用过，配置一直是死的。
 * <p>
 * 这里改为对当前容器生效的写法，并保留原意图中真正有意义的一点：控制 WebSocket 的缓冲上限。
 * JSR-356 默认的文本消息缓冲只有 8192 字节，而 /log 端点每 500ms 批量推送一次日志，
 * debug 级别下很容易超过这个上限，可能造成消息被截断或连接报错。
 *
 * @author itning
 * @since 2020/9/5 23:43
 */
@Configuration
public class CustomizationBean {

    /**
     * 日志与转码进度都是文本消息，给足上限
     */
    private static final int MAX_MESSAGE_BUFFER_SIZE = 1024 * 1024;

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BUFFER_SIZE);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BUFFER_SIZE);
        return container;
    }
}
