package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.platform.redis.utils.RedisTaskLockAdapter;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.interaction.service.BrowseHistoryService;
import com.quanta.demo0.interaction.vo.BrowseHistorySnapshotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 浏览对账任务（推荐流个性化 D3/D11/D12）。
 * 职责：定时扫描 browse_history 增量（watermark），把每对 (userId, contentId) 的
 * 首看行转发为 VIEW 行为事件，经 MQ 走画像消费者统一累加——削峰 + Inbox 幂等双保险。
 * 边界：浏览不发实时事件（D3）；eventId 用稳定格式 user.behavior.browse:{id}，
 * watermark 未推进导致的重复转发被 Inbox 幂等挡住；
 * watermark 与锁 key 均在 user:profile-* 命名空间（D12），严禁写进 user:profile: 前缀。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowseBehaviorSyncTask {

    /** 每批扫描行数（单轮批量上限，收口增量扫描的网络/DB 压力；测试经 ReflectionTestUtils 注入小值覆盖） */
    private int batchSize = 500;

    /** 单轮最大批次数：防首跑巨量时长时间占锁，剩余增量下轮继续 */
    private static final int MAX_BATCH_ROUNDS = 20;

    /** 任务锁 TTL（秒）：覆盖单轮执行时长，持有者异常退出时兜底过期 */
    private static final long LOCK_TTL_SECONDS = 600L;

    /** 浏览对账事件 ID 前缀：稳定格式保证同一行重复转发被 Inbox 幂等消化 */
    private static final String BROWSE_EVENT_ID_PREFIX = "user.behavior.browse:";

    /** 实例标识：写入锁 value，排查当前持有任务的实例 */
    private final String instanceId = "browse-sync-" + UUID.randomUUID();

    private final BrowseHistoryService browseHistoryService;
    private final FeedEventProducer feedEventProducer;
    private final RedisTaskLockAdapter taskLockAdapter;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 定时浏览对账：扫增量首看行 → 转发 VIEW 事件 → 推进 watermark。
     * 多实例部署时用轻量锁防重；本轮任何异常都不推进 watermark，下轮重扫重叠区间。
     */
    @Scheduled(fixedDelayString = "${quanta.recommend.profile.browse-sync-fixed-delay-ms:600000}")
    public void syncBrowseHistory() {
        // 多实例防重：抢不到锁直接跳过本轮，锁 key 在 user:profile-* 命名空间（D12）
        if (!taskLockAdapter.tryLock(RedisConstants.USER_PROFILE_SYNC_LOCK_KEY, LOCK_TTL_SECONDS, instanceId)) {
            log.info("浏览对账任务锁被占用，跳过本轮，instanceId={}", instanceId);
            return;
        }
        try {
            doSync();
        } finally {
            // 正常与异常路径都释放锁，避免残留阻塞下轮；TTL 兜底极端宕机
            taskLockAdapter.unlock(RedisConstants.USER_PROFILE_SYNC_LOCK_KEY);
        }
    }

    private void doSync() {
        long watermarkId = readWatermark();
        int totalEvents = 0;

        for (int round = 0; round < MAX_BATCH_ROUNDS; round++) {
            // D11：SQL 用 NOT EXISTS 只取每对 (userId, contentId) 的首看行，封死"刷详情页刷画像"
            List<BrowseHistorySnapshotVO> batch =
                    browseHistoryService.getIncrementalFirstViewSnapshots(watermarkId, batchSize);
            if (batch.isEmpty()) {
                break;
            }

            long batchMaxId = watermarkId;
            for (BrowseHistorySnapshotVO row : batch) {
                // 稳定 eventId：watermark 未推进重扫时，同一行重复转发被 Inbox 幂等挡住
                feedEventProducer.createUserBehaviorEvent(
                        row.getUserId(),
                        row.getContentId(),
                        "VIEW",
                        browseEventId(row.getId()));
                batchMaxId = Math.max(batchMaxId, row.getId());
            }

            // 全批发送成功后推进 watermark；中途异常不推进，下轮重扫重叠区间
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.USER_PROFILE_SYNC_WATERMARK_KEY,
                    String.valueOf(batchMaxId));
            watermarkId = batchMaxId;
            totalEvents += batch.size();

            // 不满一批说明增量已取空；恰好一批则继续下一轮
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (totalEvents > 0) {
            log.info("浏览对账完成，转发VIEW事件数={}，watermark={}", totalEvents, watermarkId);
        }
    }

    /** 浏览对账稳定事件 ID：同一 browse_history 行永远映射同一 eventId */
    private String browseEventId(Long browseHistoryId) {
        return BROWSE_EVENT_ID_PREFIX + browseHistoryId;
    }

    /**
     * 读取 watermark（已处理到的 browse_history.id）。
     * 无记录返回 0；脏数据（非法数字）按 0 重扫——重复事件由 Inbox 幂等消化，
     * 不能因辅助值异常让任务永久停摆。
     */
    private long readWatermark() {
        String watermark = stringRedisTemplate.opsForValue()
                .get(RedisConstants.USER_PROFILE_SYNC_WATERMARK_KEY);
        if (watermark == null) {
            return 0L;
        }
        try {
            return Long.parseLong(watermark.trim());
        } catch (NumberFormatException e) {
            log.warn("浏览对账watermark非法，按0重扫，watermark={}", watermark);
            return 0L;
        }
    }
}
