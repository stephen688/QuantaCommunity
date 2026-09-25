package com.quanta.demo0.service.Impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 轻量任务锁适配器（推荐流个性化 D4/D12）。
 * 职责：为定时任务提供 SET NX EX 抢锁与释放，多实例部署时防同任务并发执行。
 * 边界：锁 key 必须来自 user:profile-* 命名空间常量（D12），
 * 严禁落入 user:profile: 前缀（会被画像衰减任务 SCAN 误当 Hash 处理）；
 * TTL 只兜底持有者异常退出，正常路径跑完即删；
 * 极端场景（任务超时、锁过期被他人抢走后误删）接受误删窗口——
 * 任务本身幂等（Inbox/扫描幂等），重复执行一次无业务损害。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTaskLockAdapter {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试获取任务级轻量锁（SET NX EX 语义）。
     *
     * @param lockKey 锁 key，必须用 RedisConstants 中 user:profile-* 命名空间常量
     * @param ttlSeconds 锁 TTL（秒），覆盖任务预计执行时长
     * @param owner 持有者标识（实例 ID），写入锁 value 便于排查当前持有实例
     * @return 抢到锁返回 true；锁已被其他实例持有时返回 false，调用方应跳过本轮
     */
    public boolean tryLock(String lockKey, long ttlSeconds, String owner) {
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, owner, Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(locked);
    }

    /**
     * 释放任务锁（跑完即删）。异常路径由调用方在 finally 中保证调用。
     */
    public void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
