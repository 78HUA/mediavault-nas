package top.itning.yunshunas.common.socket;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转码进度推送
 *
 * @author itning
 */
@Component
@ServerEndpoint(value = "/p")
public final class ProgressWebSocket {
    private static final Logger logger = LoggerFactory.getLogger(ProgressWebSocket.class);

    /**
     * 存放Session
     * <p>
     * 必须用并发容器：<code>onOpen/onClose/onError</code> 跑在 WebSocket 容器线程上，
     * 而 <code>sendMessage</code> 跑在业务线程（ffmpeg 输出读取线程）上，
     * 三者会并发读写同一张表。原先是普通 HashMap，并发下会丢会话、
     * 甚至在扩容时造成结构性损坏。
     */
    private static final Map<String, Session> SESSION_MAP = new ConcurrentHashMap<>(16);

    public static void sendMessage(String msg) {
        SESSION_MAP.forEach((id, session) -> {
            if (!session.isOpen()) {
                SESSION_MAP.remove(id);
                return;
            }
            try {
                // 用异步发送：同步 sendText 会阻塞调用线程，
                // 而调用方是 ffmpeg 的输出读取线程 —— 一个慢客户端就能反过来拖住转码
                session.getAsyncRemote().sendText(msg);
            } catch (Exception e) {
                logger.warn("推送进度消息失败，移除会话 {}", id, e);
                SESSION_MAP.remove(id);
            }
        });
    }

    @OnOpen
    public void onOpen(Session session) {
        SESSION_MAP.put(session.getId(), session);
        if (logger.isDebugEnabled()) {
            logger.debug("progress websocket open, 当前会话数 {}", SESSION_MAP.size());
        }
    }

    @OnClose
    public void onClose(Session session) {
        // 原先 onClose 不做任何清理，已关闭的会话只能等下次广播时被顺带扫掉
        SESSION_MAP.remove(session.getId());
        if (logger.isDebugEnabled()) {
            logger.debug("progress websocket close, 当前会话数 {}", SESSION_MAP.size());
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        logger.debug("onMessage {}", message);
        session.getAsyncRemote().sendText("收到消息");
    }

    @OnError
    public void onError(Session session, Throwable error) {
        SESSION_MAP.remove(session.getId());
        logger.error("progress websocket error", error);
    }
}
