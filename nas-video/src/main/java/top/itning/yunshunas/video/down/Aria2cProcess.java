package top.itning.yunshunas.video.down;

import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.itning.yunshunas.common.config.NasProperties;
import top.itning.yunshunas.common.db.ApplicationConfig;
import top.itning.yunshunas.common.util.ProcessRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author itning
 * @since 2019/7/17 20:52
 */
@Component
public class Aria2cProcess {
    private static final Logger logger = LoggerFactory.getLogger(Aria2cProcess.class);

    /**
     * 停机时等待 aria2c 优雅退出的时间
     */
    private static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private volatile ProcessRunner.Handle handle;

    public Aria2cProcess(ApplicationConfig applicationConfig) {
        NasProperties nasProperties = applicationConfig.getSetting(NasProperties.class);
        if (Objects.isNull(nasProperties)) {
            return;
        }
        if (StringUtils.isBlank(nasProperties.getAria2cFile())) {
            return;
        }
        Thread.Builder.OfVirtual virtual = Thread.ofVirtual().name("aria2c-pool-", 0);
        virtual.start(() -> {
            List<String> command = new ArrayList<>();
            command.add(nasProperties.getAria2cFile());
            command.add("--rpc-listen-port");
            command.add("6800");
            command.add("--enable-rpc");
            command.add("--rpc-listen-all");
            try {
                // aria2c 是常驻守护进程：不设超时，但必须保留句柄，否则停机时无法回收它
                handle = ProcessRunner.start(command, line -> {
                    if (logger.isDebugEnabled() && StringUtils.isNotBlank(line)) {
                        logger.debug(line);
                    }
                });
            } catch (Exception e) {
                logger.error("start aria2c process error", e);
            }
        });
    }

    /**
     * 停机时回收 aria2c 子进程
     * <p>
     * 原实现不保存 Process 引用：应用退出后 aria2c 会成为孤儿进程继续占用 6800 端口，
     * 下次启动时要么端口冲突，要么旧实例仍在后台跑。
     */
    @PreDestroy
    public void destroy() {
        ProcessRunner.Handle current = this.handle;
        if (Objects.isNull(current)) {
            return;
        }
        logger.info("停止 aria2c 进程");
        if (!current.stop(STOP_GRACE)) {
            logger.warn("aria2c 未在 {} 秒内退出，已强制回收", STOP_GRACE.toSeconds());
        }
    }
}
