package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.feed.properties.RecommendProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 用户画像每日衰减任务（推荐流个性化 D4/D12；2026-09-24 管理员复核原子性修复）。
 * 职责：每日凌晨全量衰减 user:profile:{userId} 画像 Hash——整画像 Lua 脚本单次 EVAL 原子完成
 * HGETALL→逐 field 按配置因子缩放/低于阈值删除→写回（自然遗忘陈旧兴趣），__total 作为
 * 普通 field 统一循环同步衰减；Redis 单线程执行 Lua 期间不会插入其他命令，与并发
 * HINCRBYFLOAT 累加（UserBehaviorConsumer）无丢更新窗口。
 * 边界：SCAN 只命中画像 Hash（watermark/任务锁已按 D12 隔离在 user:profile-* 命名空间，
 * 扫描结果不含 String 类型辅助 key，不做类型过滤）；单用户失败 WARN 后继续，不中断整轮；
 * 多实例部署用轻量锁防重；衰减因子与阈值从 RecommendProperties.Profile 注入
 * （03 Task 3.1 已收口，代码内不再留常量副本）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileDecayTask {

    /** SCAN 建议批大小：分批迭代避免一次拉全量 key（禁止 KEYS） */
    private static final long SCAN_BATCH_SIZE = 500L;

    /** 任务锁 TTL（秒）：覆盖全量衰减预计执行时长，持有者异常退出时兜底过期 */
    private static final long LOCK_TTL_SECONDS = 1800L;

    /**
     * 整画像原子衰减脚本（返回值：本次处理的 field 数，0=空画像）。
     * 必须用 Lua 而不能沿用"HGETALL 快照 + Java 侧 HSET/HDEL 绝对值写回"：快照写回会把
     * 旧值×0.95 覆盖到并发消费者 HINCRBYFLOAT 刚累加的分数上（丢行为计数），HDEL 分支更会
     * 按快照误删并发累加后的整个 field——Inbox 已标 SUCCESS、D10 重建任务本期不做，
     * 丢更新不可自愈（2026-09-24 管理员复核发现，独立审查漏检）。
     * HGETALL→逐 field 缩放/删除→写回全部收口在单次 EVAL 内，Redis 单线程执行 Lua
     * 期间不会插入其他命令，丢更新窗口归零。
     * 数值格式：Lua tostring 为 %.14g 口径，与 HINCRBYFLOAT 存储的十进制字符串及下游
     * Double.parseDouble 兼容（管理员复核确认，与原 Java String.valueOf(double) 输出精度语义一致）。
     */
    private static final String DECAY_PROFILE_LUA = """
            local entries = redis.call('HGETALL', KEYS[1])
            local count = 0
            for i = 1, #entries, 2 do
              local field = entries[i]
              local newScore = tonumber(entries[i+1]) * tonumber(ARGV[1])
              if newScore < tonumber(ARGV[2]) then
                redis.call('HDEL', KEYS[1], field)
              else
                redis.call('HSET', KEYS[1], field, tostring(newScore))
              end
              count = count + 1
            end
            return count
            """;

    /** 整画像原子衰减脚本执行器：Long 结果为处理的 field 数（0=空画像） */
    private static final DefaultRedisScript<Long> DECAY_PROFILE_SCRIPT =
            new DefaultRedisScript<>(DECAY_PROFILE_LUA, Long.class);

    /** 实例标识：写入锁 value，排查当前持有任务的实例 */
    private final String instanceId = "profile-decay-" + UUID.randomUUID();

    private final RedisTaskLockAdapter taskLockAdapter;
    private final StringRedisTemplate stringRedisTemplate;

    /** 衰减参数（D4）：每日因子与删除阈值，唯一真源 quanta.recommend.profile */
    private final RecommendProperties recommendProperties;

    /**
     * 每日画像衰减：SCAN 全部画像 Hash → 逐画像以整画像 Lua 脚本单次 EVAL 原子衰减
     * （枚举、缩放、阈值删除全部在 Redis 侧一次完成）。
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
     * 衰减单个用户画像：整画像 Lua 脚本单次 EVAL 原子执行——脚本内 HGETALL 枚举当前 field、
     * 逐 field 按配置因子缩放、低于阈值删除、其余写回，Java 侧不持有任何快照值，
     * 与并发 HINCRBYFLOAT 累加无丢更新窗口。
     * __total 是画像 Hash 的普通 field，脚本统一循环天然同步衰减（α 数据源与标签分数保持一致）；
     * 衰减期间新出现的 field 不在本次 HGETALL 结果中，顺延下轮衰减（新增兴趣首日保满权重）。
     *
     * @return 画像非空（脚本处理了至少一个 field）返回 true；空画像（脚本返回 0）返回 false
     */
    private boolean decaySingleProfile(String profileKey) {
        RecommendProperties.Profile profileConfig = recommendProperties.getProfile();
        double decayDailyFactor = profileConfig.getDecayDailyFactor();
        double decayMinScore = profileConfig.getDecayMinScore();

        // 单次 EVAL：枚举/缩放/删除/写回全在脚本内原子完成，factor/threshold 仅作脚本参数传入
        Long decayedFieldCount = stringRedisTemplate.execute(
                DECAY_PROFILE_SCRIPT,
                List.of(profileKey),
                String.valueOf(decayDailyFactor),
                String.valueOf(decayMinScore));
        return decayedFieldCount != null && decayedFieldCount > 0;
    }
}
