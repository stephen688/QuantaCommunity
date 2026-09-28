package com.quanta.demo0.service.Impl;

import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.interaction.entity.BrowseHistory;
import com.quanta.demo0.mapper.BrowseHistoryMapper;
import com.quanta.demo0.service.OutboxEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BrowseBehaviorSyncTask 的浏览对账测试（推荐流个性化 D3/D11/D12）。
 * 断言：扫表 → 稳定 eventId 转发 VIEW 事件 → 全批成功后推进 watermark；
 * 发送异常不推进 watermark（下轮重扫由 Inbox 幂等消化）；
 * 抢锁失败直接跳过；watermark 脏数据按零起步防御。
 * Mapper 造数对齐真实唯一键 (user_id, content_id, browse_date)：
 * 同一对跨天多行是常态数据形态，NOT EXISTS 过滤后只保留首看行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BrowseBehaviorSyncTaskTest {

    private static final String WATERMARK_KEY = RedisConstants.USER_PROFILE_SYNC_WATERMARK_KEY;
    private static final String LOCK_KEY = RedisConstants.USER_PROFILE_SYNC_LOCK_KEY;

    @Mock
    private BrowseHistoryMapper browseHistoryMapper;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private RedisTaskLockAdapter taskLockAdapter;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private BrowseBehaviorSyncTask task;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 默认抢锁成功；个别用例单独覆盖失败分支
        when(taskLockAdapter.tryLock(eq(LOCK_KEY), anyLong(), anyString())).thenReturn(true);
        task = new BrowseBehaviorSyncTask(browseHistoryMapper, outboxEventService, taskLockAdapter, stringRedisTemplate);
    }

    private BrowseHistory row(Long id, Long userId, Long contentId, LocalDate browseDate) {
        return BrowseHistory.builder()
                .id(id)
                .userId(userId)
                .contentId(contentId)
                .browseDate(browseDate)
                .isDeleted(0)
                .build();
    }

    @Test
    void 正常流_watermark零起步_三行三事件_watermark推进到最大id() {
        when(valueOperations.get(WATERMARK_KEY)).thenReturn(null);
        when(browseHistoryMapper.selectIncrementalFirstViews(0L, 500)).thenReturn(List.of(
                row(1L, 3L, 10L, LocalDate.of(2026, 9, 23)),
                row(2L, 4L, 20L, LocalDate.of(2026, 9, 23)),
                row(3L, 5L, 30L, LocalDate.of(2026, 9, 22))
        ));

        task.syncBrowseHistory();

        // 三个 VIEW 事件，eventId 必须是稳定格式 user.behavior.browse:{browseHistoryId}
        verify(outboxEventService).createUserBehaviorEvent(3L, 10L, "VIEW", "user.behavior.browse:1");
        verify(outboxEventService).createUserBehaviorEvent(4L, 20L, "VIEW", "user.behavior.browse:2");
        verify(outboxEventService).createUserBehaviorEvent(5L, 30L, "VIEW", "user.behavior.browse:3");
        // 全批发送成功后 watermark 推进到本批最大 id
        verify(valueOperations).set(WATERMARK_KEY, "3");
        // 任务结束释放锁
        verify(taskLockAdapter).unlock(LOCK_KEY);
    }

    @Test
    void 首看去重_D11_同对跨天多行只发一次事件() {
        // 真实唯一键 (user_id, content_id, browse_date)：u3/c10 跨三天三行 + u4/c20 一行；
        // NOT EXISTS 过滤后 Mapper 只返回首看行，任务只按返回行转发
        when(valueOperations.get(WATERMARK_KEY)).thenReturn(null);
        when(browseHistoryMapper.selectIncrementalFirstViews(0L, 500)).thenReturn(List.of(
                row(1L, 3L, 10L, LocalDate.of(2026, 9, 21)),
                row(4L, 4L, 20L, LocalDate.of(2026, 9, 23))
        ));

        task.syncBrowseHistory();

        // (3,10) 只发一次（id=1 首看行），(4,20) 一次
        verify(outboxEventService, times(1)).createUserBehaviorEvent(eq(3L), eq(10L), eq("VIEW"), anyString());
        verify(outboxEventService, times(1)).createUserBehaviorEvent(eq(4L), eq(20L), eq("VIEW"), anyString());
        verify(outboxEventService, times(1)).createUserBehaviorEvent(3L, 10L, "VIEW", "user.behavior.browse:1");
        verify(valueOperations).set(WATERMARK_KEY, "4");
    }

    @Test
    void 中途发送异常_watermark不推进_锁仍释放() {
        when(valueOperations.get(WATERMARK_KEY)).thenReturn(null);
        when(browseHistoryMapper.selectIncrementalFirstViews(0L, 500)).thenReturn(List.of(
                row(1L, 3L, 10L, LocalDate.of(2026, 9, 23)),
                row(2L, 4L, 20L, LocalDate.of(2026, 9, 23)),
                row(3L, 5L, 30L, LocalDate.of(2026, 9, 23))
        ));
        when(outboxEventService.createUserBehaviorEvent(eq(3L), eq(10L), eq("VIEW"), anyString()))
                .thenReturn("evt-1");
        when(outboxEventService.createUserBehaviorEvent(eq(4L), eq(20L), eq("VIEW"), anyString()))
                .thenThrow(new RuntimeException("outbox insert failed"));

        // 异常向上抛（Spring 调度记录），watermark 保持旧值，下轮重扫重叠区间
        assertThrows(RuntimeException.class, () -> task.syncBrowseHistory());

        verify(valueOperations, never()).set(eq(WATERMARK_KEY), anyString());
        // finally 释放锁，避免异常退出后锁残留阻塞下轮
        verify(taskLockAdapter).unlock(LOCK_KEY);
    }

    @Test
    void 无增量_零事件_watermark不动() {
        when(valueOperations.get(WATERMARK_KEY)).thenReturn("7");
        when(browseHistoryMapper.selectIncrementalFirstViews(7L, 500)).thenReturn(List.of());

        task.syncBrowseHistory();

        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any(), any());
        verify(valueOperations, never()).set(eq(WATERMARK_KEY), anyString());
        verify(taskLockAdapter).unlock(LOCK_KEY);
    }

    @Test
    void 抢锁失败_直接返回零调用() {
        when(taskLockAdapter.tryLock(eq(LOCK_KEY), anyLong(), anyString())).thenReturn(false);

        task.syncBrowseHistory();

        verifyNoInteractions(browseHistoryMapper, outboxEventService);
        verify(stringRedisTemplate, never()).opsForValue();
        verify(taskLockAdapter, never()).unlock(LOCK_KEY);
    }

    @Test
    void 多批循环_五行批大小二_三批全处理() {
        ReflectionTestUtils.setField(task, "batchSize", 2);
        when(valueOperations.get(WATERMARK_KEY)).thenReturn(null);
        when(browseHistoryMapper.selectIncrementalFirstViews(0L, 2)).thenReturn(List.of(
                row(1L, 3L, 10L, LocalDate.of(2026, 9, 23)),
                row(2L, 4L, 20L, LocalDate.of(2026, 9, 23))
        ));
        when(browseHistoryMapper.selectIncrementalFirstViews(2L, 2)).thenReturn(List.of(
                row(3L, 5L, 30L, LocalDate.of(2026, 9, 23)),
                row(4L, 6L, 40L, LocalDate.of(2026, 9, 23))
        ));
        when(browseHistoryMapper.selectIncrementalFirstViews(4L, 2)).thenReturn(List.of(
                row(5L, 7L, 50L, LocalDate.of(2026, 9, 23))
        ));

        task.syncBrowseHistory();

        verify(outboxEventService, times(5)).createUserBehaviorEvent(any(), any(), eq("VIEW"), anyString());
        // watermark 逐批推进：2 → 4 → 5
        InOrder inOrder = inOrder(valueOperations);
        inOrder.verify(valueOperations).set(WATERMARK_KEY, "2");
        inOrder.verify(valueOperations).set(WATERMARK_KEY, "4");
        inOrder.verify(valueOperations).set(WATERMARK_KEY, "5");
    }

    @Test
    void watermark非零起点_从watermark之后扫描() {
        when(valueOperations.get(WATERMARK_KEY)).thenReturn("10");
        when(browseHistoryMapper.selectIncrementalFirstViews(10L, 500)).thenReturn(List.of(
                row(11L, 3L, 10L, LocalDate.of(2026, 9, 23))
        ));

        task.syncBrowseHistory();

        verify(browseHistoryMapper).selectIncrementalFirstViews(10L, 500);
        verify(outboxEventService).createUserBehaviorEvent(3L, 10L, "VIEW", "user.behavior.browse:11");
        verify(valueOperations).set(WATERMARK_KEY, "11");
    }

    @Test
    void watermark脏数据_按零起步防御不抛异常() {
        when(valueOperations.get(WATERMARK_KEY)).thenReturn("not-a-number");
        when(browseHistoryMapper.selectIncrementalFirstViews(0L, 500)).thenReturn(List.of());

        // 辅助值脏数据不能让任务永久停摆：按 0 重扫，重复事件由 Inbox 幂等消化
        assertDoesNotThrow(() -> task.syncBrowseHistory());

        verify(browseHistoryMapper).selectIncrementalFirstViews(0L, 500);
    }
}
