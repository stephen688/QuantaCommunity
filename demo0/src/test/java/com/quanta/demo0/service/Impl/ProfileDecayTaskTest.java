package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ProfileDecayTask 的每日画像衰减测试（推荐流个性化 D4/D12）。
 * 断言：全部画像 field ×0.95、低于 0.5 的 field 删除、__total 同步衰减；
 * SCAN 按批迭代多批 cursor；单用户失败 WARN 继续不中断整轮；
 * 抢锁失败直接跳过；锁参数用衰减锁 key（user:profile-decay:lock，D12 隔离命名空间）与 1800 秒 TTL。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProfileDecayTaskTest {

    private static final String DECAY_LOCK_KEY = RedisConstants.USER_PROFILE_DECAY_LOCK_KEY;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private RedisTaskLockAdapter taskLockAdapter;

    /** SCAN 游标 mock：由各用例按批构造 hasNext/next 序列 */
    private Cursor<String> scanCursor;

    private ProfileDecayTask task;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        // 默认抢锁成功；抢锁失败用例单独覆盖
        when(taskLockAdapter.tryLock(eq(DECAY_LOCK_KEY), anyLong(), anyString())).thenReturn(true);
        task = new ProfileDecayTask(taskLockAdapter, stringRedisTemplate);
    }

    /** 构造 SCAN 游标：依次返回给定画像 key，之后游标结束 */
    private void stubScanCursor(String... profileKeys) {
        scanCursor = mock(Cursor.class);
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(scanCursor);
        // 计数器式应答：hasNext 返回 n 次 true 后结束，next 依序吐出画像 key
        java.util.concurrent.atomic.AtomicInteger cursorIndex = new java.util.concurrent.atomic.AtomicInteger();
        when(scanCursor.hasNext()).thenAnswer(invocation -> cursorIndex.get() < profileKeys.length);
        when(scanCursor.next()).thenAnswer(invocation -> profileKeys[cursorIndex.getAndIncrement()]);
    }

    @Test
    void 正常衰减_低于阈值field删除_其余按因子缩放_总分同步衰减() {
        stubScanCursor("user:profile:3");
        when(hashOperations.entries("user:profile:3")).thenReturn(Map.of(
                "life", "10",
                "professional", "0.4",
                "__total", "10.4"
        ));

        task.decayAllProfiles();

        // life：10×0.95=9.5 ≥ 0.5 覆盖写回；professional：0.4×0.95=0.38 < 0.5 删除；
        // __total：10.4×0.95=9.88 同步衰减（普通 field 统一循环）
        ArgumentCaptor<String> lifeValue = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq("user:profile:3"), eq("life"), lifeValue.capture());
        assertEquals(9.5, Double.parseDouble(lifeValue.getValue()), 1e-9);

        ArgumentCaptor<String> totalValue = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq("user:profile:3"), eq("__total"), totalValue.capture());
        assertEquals(9.88, Double.parseDouble(totalValue.getValue()), 1e-9);

        verify(hashOperations).delete("user:profile:3", "professional");
        // 任务结束释放锁
        verify(taskLockAdapter).unlock(DECAY_LOCK_KEY);
    }

    @Test
    void 抢锁失败_直接跳过_零扫描零画像操作() {
        when(taskLockAdapter.tryLock(eq(DECAY_LOCK_KEY), anyLong(), anyString())).thenReturn(false);

        task.decayAllProfiles();

        verify(stringRedisTemplate, never()).scan(any(ScanOptions.class));
        verifyNoInteractions(hashOperations);
        // 未抢到锁不释放他人持有的锁
        verify(taskLockAdapter, never()).unlock(DECAY_LOCK_KEY);
    }

    @Test
    void SCAN多批cursor_全部用户都被衰减() {
        stubScanCursor("user:profile:3", "user:profile:4", "user:profile:5");
        when(hashOperations.entries(anyString())).thenReturn(Map.of("life", "10"));

        task.decayAllProfiles();

        // 三批游标返回的三个画像 key 都被读取并衰减
        verify(hashOperations).entries("user:profile:3");
        verify(hashOperations).entries("user:profile:4");
        verify(hashOperations).entries("user:profile:5");
        verify(hashOperations, times(3)).put(anyString(), eq("life"), anyString());
    }

    @Test
    void 单用户处理失败_WARN继续下一个用户() {
        stubScanCursor("user:profile:3", "user:profile:4");
        when(hashOperations.entries("user:profile:3")).thenThrow(new RuntimeException("redis hgetall failed"));
        when(hashOperations.entries("user:profile:4")).thenReturn(Map.of("life", "10"));

        // 衰减任务对单 key 失败不整体中断，异常被吞成 WARN 日志
        assertDoesNotThrow(() -> task.decayAllProfiles());

        verify(hashOperations).entries("user:profile:4");
        ArgumentCaptor<String> lifeValue = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq("user:profile:4"), eq("life"), lifeValue.capture());
        assertEquals(9.5, Double.parseDouble(lifeValue.getValue()), 1e-9);
        // 失败用户照常处理完，锁仍释放
        verify(taskLockAdapter).unlock(DECAY_LOCK_KEY);
    }

    @Test
    void 锁参数_衰减锁key与1800秒TTL() {
        stubScanCursor();

        task.decayAllProfiles();

        // 只断言 SET NX 调用参数：锁 key 用 D12 隔离命名空间常量，TTL 覆盖全量衰减时长
        verify(taskLockAdapter).tryLock(eq(DECAY_LOCK_KEY), eq(1800L), anyString());
    }

    @Test
    void 空画像Hash_不写回不删除() {
        stubScanCursor("user:profile:3");
        when(hashOperations.entries("user:profile:3")).thenReturn(Map.of());

        task.decayAllProfiles();

        verify(hashOperations, never()).put(anyString(), any(), any());
        verify(hashOperations, never()).delete(anyString(), any());
    }
}
