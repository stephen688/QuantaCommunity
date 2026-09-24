package com.quanta.demo0.service.Impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisTaskLockAdapter 的轻量任务锁测试（推荐流个性化 D4/D12）。
 * 断言：tryLock 委托 SET NX EX（key/owner/TTL 三个调用参数）、
 * 抢锁失败返回 false、unlock 删除锁 key。
 * 锁 key 均在 user:profile-* 命名空间（D12），不落入 user:profile: 画像扫描范围。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisTaskLockAdapterTest {

    private static final String LOCK_KEY = "user:profile-sync:lock";
    private static final String OWNER = "task-instance-1";
    private static final long TTL_SECONDS = 600L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisTaskLockAdapter adapter;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        adapter = new RedisTaskLockAdapter(stringRedisTemplate);
    }

    @Test
    void tryLock_委托setIfAbsent_锁key_owner_TTL三参数完整传递() {
        when(valueOperations.setIfAbsent(LOCK_KEY, OWNER, Duration.ofSeconds(TTL_SECONDS))).thenReturn(true);

        assertTrue(adapter.tryLock(LOCK_KEY, TTL_SECONDS, OWNER));

        // SET NX EX 语义：key 不存在才写入，value 记录持有者便于排查，TTL 兜底异常退出
        verify(valueOperations).setIfAbsent(LOCK_KEY, OWNER, Duration.ofSeconds(TTL_SECONDS));
    }

    @Test
    void tryLock_锁已被持有_返回false() {
        // setIfAbsent 返回 false（或 Redis 异常返回 null）都视为抢锁失败
        when(valueOperations.setIfAbsent(LOCK_KEY, OWNER, Duration.ofSeconds(TTL_SECONDS))).thenReturn(false);

        assertFalse(adapter.tryLock(LOCK_KEY, TTL_SECONDS, OWNER));
    }

    @Test
    void tryLock_redis返回null_按失败处理() {
        when(valueOperations.setIfAbsent(LOCK_KEY, OWNER, Duration.ofSeconds(TTL_SECONDS))).thenReturn(null);

        assertFalse(adapter.tryLock(LOCK_KEY, TTL_SECONDS, OWNER));
    }

    @Test
    void unlock_删除锁key() {
        adapter.unlock(LOCK_KEY);

        verify(stringRedisTemplate).delete(LOCK_KEY);
    }
}
