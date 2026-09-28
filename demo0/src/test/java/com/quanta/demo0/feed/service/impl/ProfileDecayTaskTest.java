package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.platform.redis.utils.RedisTaskLockAdapter;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.feed.properties.RecommendProperties;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
 * ProfileDecayTask 的每日画像衰减测试（推荐流个性化 D4/D12，2026-09-24 管理员复核原子性修复）。
 * 断言：单画像衰减收口为整画像 Lua 脚本单次 EVAL——verify execute(script, [profileKey], factor, threshold)，
 * 枚举（HGETALL）/缩放（HSET）/阈值删除（HDEL）/计数全部在脚本内原子完成，Java 侧不持有任何快照值，
 * 与并发 HINCRBYFLOAT 累加无丢更新窗口（衰减路径 verify never entries/put/delete 防读改写回退）；
 * 脚本 count 返回值（>0=画像非空 / 0=空画像）映射既有契约 boolean；
 * 因子/阈值唯一真源为 RecommendProperties.Profile（D4，含自定义值生效验证）；
 * SCAN 按批迭代多批 cursor；单用户脚本失败 WARN 继续不中断整轮；
 * 抢锁失败直接跳过；锁参数用衰减锁 key（user:profile-decay:lock，D12 隔离命名空间）与 1800 秒 TTL。
 * 数值缩放与删除的真实 Redis 行为由 RecommendRerankRedisIntegrationTests 场景 5 覆盖。
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

    /** 衰减参数配置：默认因子 0.95 / 阈值 0.5，个别用例单独覆盖自定义值 */
    private final RecommendProperties recommendProperties = new RecommendProperties();

    private ProfileDecayTask task;

    /** SCAN 游标 mock：由各用例按批构造 hasNext/next 序列 */
    private Cursor<String> scanCursor;

    @BeforeEach
    void setUp() {
        // hashOperations 仅供防回退断言（verify never entries/put/delete），衰减本体不得触碰
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        // 默认抢锁成功；抢锁失败用例单独覆盖
        when(taskLockAdapter.tryLock(eq(DECAY_LOCK_KEY), anyLong(), anyString())).thenReturn(true);
        task = new ProfileDecayTask(taskLockAdapter, stringRedisTemplate, recommendProperties);
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

    /** 整画像脚本按用户应答：profileKey → 脚本 count 返回值（处理的 field 数，0=空画像），缺省 1 */
    private void stubScriptCountsByUser(Map<String, Long> countsByUser) {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    List<?> keys = invocation.getArgument(1);
                    return countsByUser.getOrDefault(String.valueOf(keys.get(0)), 1L);
                });
    }

    @Test
    void 正常衰减_整画像单次原子脚本_缩放删除计数全在脚本内_无读改写序列() {
        stubScanCursor("user:profile:3");
        // 整画像脚本一次 EVAL 处理全部 field（原语义：life 按因子缩放、professional 低于阈值删除、__total 同步衰减）
        stubScriptCountsByUser(Map.of("user:profile:3", 3L));

        task.decayAllProfiles();

        // 单画像只执行一次脚本：factor/minScore 作为脚本参数传入（0.95/0.5），key 列表只含本画像
        ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(stringRedisTemplate, times(1)).execute(
                scriptCaptor.capture(), eq(List.of("user:profile:3")), eq("0.95"), eq("0.5"));

        // 枚举/删除/写回必须全部收口在单个 Lua 脚本内（HGETALL 枚举当前 field、HDEL 阈值删除、HSET 缩放写回）
        String luaText = scriptCaptor.getValue().getScriptAsString();
        assertTrue(luaText.contains("HGETALL"), "脚本内必须 HGETALL 枚举画像 field，不能用 Java 侧快照");
        assertTrue(luaText.contains("HDEL"), "低于阈值删除必须在脚本内原子完成");
        assertTrue(luaText.contains("HSET"), "缩放写回必须在脚本内原子完成");
        assertEquals(Long.class, scriptCaptor.getValue().getResultType());

        // 缺陷收口断言：衰减路径不允许出现任何 Java 侧读-改-写序列（防快照覆盖并发 HINCRBYFLOAT 回退）
        verify(hashOperations, never()).entries(anyString());
        verify(hashOperations, never()).put(anyString(), any(), any());
        verify(hashOperations, never()).delete(anyString(), any());
        // 任务结束释放锁
        verify(taskLockAdapter).unlock(DECAY_LOCK_KEY);
    }

    @Test
    void 抢锁失败_直接跳过_零扫描零画像操作() {
        when(taskLockAdapter.tryLock(eq(DECAY_LOCK_KEY), anyLong(), anyString())).thenReturn(false);

        task.decayAllProfiles();

        verify(stringRedisTemplate, never()).scan(any(ScanOptions.class));
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(), any());
        verifyNoInteractions(hashOperations);
        // 未抢到锁不释放他人持有的锁
        verify(taskLockAdapter, never()).unlock(DECAY_LOCK_KEY);
    }

    @Test
    void SCAN多批cursor_全部用户都被衰减() {
        stubScanCursor("user:profile:3", "user:profile:4", "user:profile:5");
        stubScriptCountsByUser(Map.of());

        task.decayAllProfiles();

        // 三批游标返回的三个画像 key 各自整画像送入原子脚本（每用户一次 EVAL，factor/minScore 同参）
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of("user:profile:3")), eq("0.95"), eq("0.5"));
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of("user:profile:4")), eq("0.95"), eq("0.5"));
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of("user:profile:5")), eq("0.95"), eq("0.5"));
        verify(stringRedisTemplate, times(3)).execute(
                any(DefaultRedisScript.class), anyList(), anyString(), anyString());
        verify(hashOperations, never()).entries(anyString());
    }

    @Test
    void 单用户脚本执行失败_WARN继续下一个用户() {
        stubScanCursor("user:profile:3", "user:profile:4");
        // user3 的脚本执行抛错（如 Redis 侧异常），user4 正常返回处理 field 数
        Map<String, Long> countsByUser = new HashMap<>();
        countsByUser.put("user:profile:3", 1L);
        countsByUser.put("user:profile:4", 1L);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    List<?> keys = invocation.getArgument(1);
                    if (keys.contains("user:profile:3")) {
                        throw new IllegalStateException("lua eval failed");
                    }
                    return 1L;
                });

        // 衰减任务对单 key 失败不整体中断，异常被吞成 WARN 日志
        assertDoesNotThrow(() -> task.decayAllProfiles());

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of("user:profile:4")), eq("0.95"), eq("0.5"));
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
    void 脚本返回0_空画像_不计数且无任何读写() {
        stubScanCursor("user:profile:3");
        // 新契约下空画像由脚本 count 返回 0 表达（脚本内 HGETALL 为空），Java 侧零画像读写
        stubScriptCountsByUser(Map.of("user:profile:3", 0L));

        assertDoesNotThrow(() -> task.decayAllProfiles());

        // 空画像：脚本照常执行一次，但不允许任何 Java 侧 Hash 读写补救
        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of("user:profile:3")), eq("0.95"), eq("0.5"));
        verify(hashOperations, never()).entries(anyString());
        verify(hashOperations, never()).put(anyString(), any(), any());
        verify(hashOperations, never()).delete(anyString(), any());
        verify(taskLockAdapter).unlock(DECAY_LOCK_KEY);
    }

    @Test
    void 自定义衰减因子_按注入值缩放() {
        // 03 Task 3.1 收口验证：衰减时读取注入配置并作为脚本参数传给 Redis，非构造期常量快照
        recommendProperties.getProfile().setDecayDailyFactor(0.5);
        stubScanCursor("user:profile:3");
        stubScriptCountsByUser(Map.of("user:profile:3", 1L));

        task.decayAllProfiles();

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of("user:profile:3")), eq("0.5"), eq("0.5"));
    }
}
