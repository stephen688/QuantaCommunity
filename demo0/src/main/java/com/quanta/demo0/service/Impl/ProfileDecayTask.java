package com.quanta.demo0.service.Impl;

import com.quanta.demo0.constant.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 用户画像每日衰减任务（推荐流个性化 D4/D12）。
 * 职责：每日凌晨全量衰减 user:profile:{userId} 画像 Hash——每个 field 分数 ×0.95，
 * 低于 0.5 的 field 删除（自然遗忘陈旧兴趣）；__total 作为普通 field 统一循环，同步衰减。
 * 边界：SCAN 只命中画像 Hash（watermark/任务锁已按 D12 隔离在 user:profile-* 命名空间，
 * 扫描结果不含 String 类型辅助 key，不做类型过滤）；单用户失败 WARN 后继续，不中断整轮；
 * 多实例部署用轻量锁防重；衰减因子与阈值为常量，03 Task 3.1 统一收口进配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileDecayTask {

    /** 每日衰减因子：field × 0.95（03 Task 3.1 收口进配置前的常量真源） */
    private static final double DECAY_DAILY_FACTOR = 0.95;

    /** 低于该阈值的 field 视为陈旧兴趣，直接删除，画像 Hash 不无限膨胀 */
    private static final double DECAY_MIN_SCORE = 0.5;

    /** SCAN 建议批大小：分批迭代避免一次拉全量 key（禁止 KEYS） */
    private static final long SCAN_BATCH_SIZE = 500L;

    /** 任务锁 TTL（秒）：覆盖全量衰减预计执行时长，持有者异常退出时兜底过期 */
    private static final long LOCK_TTL_SECONDS = 1800L;

    /** 实例标识：写入锁 value，排查当前持有任务的实例 */
    private final String instanceId = "profile-decay-" + UUID.randomUUID();

    private final RedisTaskLockAdapter taskLockAdapter;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 每日画像衰减：SCAN 全部画像 Hash → 逐 field ×0.95，低于 0.5 删除。
     * 多实例部署时轻量锁防重；单用户处理失败只 WARN 继续，不影响其余用户。
     */
    @Scheduled(cron = "${quanta.recommend.profile.decay-cron:0 0 4 * * ?}")
    public void decayAllProfiles() {
        // 多实例防重：抢不到锁直接跳过本轮，锁 key 在 user:profile-* 命名空间（D12）
        if (!taskLockAdapter.tryLock(RedisConstants.USER_PROFILE_DECAY_LOCK_KEY, LOCK_TTL_SECONDS, instanceId)) {
            log.info("画像衰减任务锁被占用，跳过本轮，instanceId={}", instanceId);
            return;
        }
        try {
            doDecay();
        } finally {
            // 正常与异常路径都释放锁，避免残留阻塞下轮；TTL 兜底极端宕机
            taskLockAdapter.unlock(RedisConstants.USER_PROFILE_DECAY_LOCK_KEY);
        }
    }

    private void doDecay() {
        int decayedUserCount = 0;
        // SCAN 按 COUNT 分批迭代画像 key；辅助 key 已隔离在 user:profile-*，扫描结果只含 Hash（D12）
        try (Cursor<String> cursor = stringRedisTemplate.scan(ScanOptions.scanOptions()
                .match(RedisConstants.USER_PROFILE_KEY + "*")
                .count(SCAN_BATCH_SIZE)
                .build())) {
            while (cursor.hasNext()) {
                String profileKey = cursor.next();
                try {
                    if (decaySingleProfile(profileKey)) {
                        decayedUserCount++;
                    }
                } catch (Exception e) {
                    // 单用户失败不中断整轮：其余画像照常衰减，失败原因留日志排查
                    log.warn("画像衰减失败，继续下一个用户，profileKey={}", profileKey, e);
                }
            }
        }
        if (decayedUserCount > 0) {
            log.info("画像衰减完成，衰减用户数={}", decayedUserCount);
        }
    }

    /**
     * 衰减单个用户画像：每个 field ×DECAY_DAILY_FACTOR，结果低于 DECAY_MIN_SCORE 的 field 删除。
     * __total 是画像 Hash 的普通 field，统一循环天然同步衰减（α 数据源与标签分数保持一致）。
     *
     * @return 画像非空（有 field 被读取处理）返回 true；空画像返回 false
     */
    private boolean decaySingleProfile(String profileKey) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(profileKey);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = String.valueOf(entry.getKey());
            double oldScore = Double.parseDouble(String.valueOf(entry.getValue()));
            double newScore = oldScore * DECAY_DAILY_FACTOR;
            if (newScore < DECAY_MIN_SCORE) {
                // 陈旧兴趣：删除而非保留极小值，控制画像 Hash 体量
                stringRedisTemplate.opsForHash().delete(profileKey, field);
            } else {
                stringRedisTemplate.opsForHash().put(profileKey, field, String.valueOf(newScore));
            }
        }
        return true;
    }
}
