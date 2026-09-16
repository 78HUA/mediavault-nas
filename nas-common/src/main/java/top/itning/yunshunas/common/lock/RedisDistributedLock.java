package top.itning.yunshunas.common.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import top.itning.yunshunas.common.config.NasRedisConfig;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Redis 的分布式锁
 * <p>
 * 加锁用 <code>SET key value NX PX ttl</code>（一条命令同时完成「不存在才设置」与「设置过期」，
 * 避免先 set 再 expire 之间进程挂掉导致死锁）；
 * 解锁用 Lua 脚本先比对令牌再删除，保证「判断是不是自己的锁」与「删除」是原子的 ——
 * 否则可能出现「A 的锁已超时释放、B 拿到锁，此时 A 执行完删掉了 B 的锁」。
 * <p>
 * <b>降级策略：</b>Redis 未启用或不可用时一律**放行**，由调用方自己的本机去重兜底。
 * 中间件故障不应该让业务直接不可用。
 *
 * @author 78HUA
 */
@Slf4j
@Component
public class RedisDistributedLock {

    /**
     * 释放锁：仅当值仍是自己的令牌时才删除
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final NasRedisConfig nasRedisConfig;

    @Autowired
    public RedisDistributedLock(NasRedisConfig nasRedisConfig) {
        this.nasRedisConfig = nasRedisConfig;
    }

    /**
     * 锁令牌
     *
     * @param key         锁的键
     * @param token       持有者令牌
     * @param redisBacked 是否真的由 Redis 持有；<code>false</code> 表示 Redis 不可用、本次未真正加锁
     */
    public record LockToken(String key, String token, boolean redisBacked) {

        static LockToken localOnly(String key) {
            return new LockToken(key, null, false);
        }
    }

    /**
     * 尝试获取锁
     *
     * @param key 锁的键
     * @param ttl 锁的存活时间
     * @return 获取成功返回令牌；被他人占用返回空
     */
    public Optional<LockToken> tryLock(String key, Duration ttl) {
        if (!nasRedisConfig.enabled()) {
            return Optional.of(LockToken.localOnly(key));
        }
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = nasRedisConfig.getRedisTemplate().opsForValue().setIfAbsent(key, token, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                return Optional.of(new LockToken(key, token, true));
            }
            return Optional.empty();
        } catch (Exception e) {
            // 放行而不是拒绝：Redis 故障时退化为本机去重，业务不中断
            log.warn("获取分布式锁失败，本次退化为单机去重：{}", e.getMessage());
            return Optional.of(LockToken.localOnly(key));
        }
    }

    /**
     * 释放锁
     *
     * @param lockToken 加锁时拿到的令牌；为 <code>null</code> 时忽略
     */
    public void unlock(LockToken lockToken) {
        if (lockToken == null || !lockToken.redisBacked()) {
            return;
        }
        try {
            Long released = nasRedisConfig.getRedisTemplate().execute(RELEASE_SCRIPT,
                    Collections.singletonList(lockToken.key()), lockToken.token());
            if (released == null || released == 0L) {
                log.warn("锁已不属于当前持有者（多半是已超时并被人接管）：{}", lockToken.key());
            }
        } catch (Exception e) {
            // 释放失败不影响业务结果：锁最终会因超时自动释放
            log.warn("释放分布式锁失败：{}", e.getMessage());
        }
    }
}
