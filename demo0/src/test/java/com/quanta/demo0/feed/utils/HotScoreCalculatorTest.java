package com.quanta.demo0.feed.utils;

import com.quanta.demo0.content.vo.ContentSnapshotVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HotScoreCalculator 热度公式唯一真源的纯函数测试（推荐流个性化 03 Task 3.2 提取）。
 * 断言：基础公式 (liked×3 + comment×2 + collect×5) / (hours+2)^1.5；
 * 无互动内容 20 分保底；计数与时间 null 安全。
 * 公式从 ContentServiceImpl / ContentExposureServiceImpl 提取后此两处调用点行为不变。
 */
class HotScoreCalculatorTest {

    private static final LocalDateTime FIXED_CREATE_TIME = LocalDateTime.of(2026, 9, 24, 0, 0, 0);

    private ContentSnapshotVO content(Integer liked, Integer commentCount, Integer collectCount, LocalDateTime createTime) {
        return ContentSnapshotVO.builder()
                .contentId(1L)
                .likedCount(liked)
                .commentCount(commentCount)
                .collectCount(collectCount)
                .createTime(createTime)
                .build();
    }

    @Test
    void 基础公式_加权求和除以时间衰减() {
        // 发布 10 小时：liked=10,comment=2,collect=4 → baseScore=10×3+2×2+4×5=54；decay=(10+2)^1.5
        ContentSnapshotVO content = content(10, 2, 4, LocalDateTime.now().minusHours(10));

        double hotScore = HotScoreCalculator.calculate(content);

        double expectedBase = 10 * 3 + 2 * 2 + 4 * 5;
        // minusHours(10) 与 now() 之间可能跨过整点边界，用宽松 hours ∈ [10,11] 区间断言
        double decayWith10 = Math.pow(10 + 2, 1.5);
        double decayWith11 = Math.pow(11 + 2, 1.5);
        assertTrue(hotScore <= expectedBase / decayWith10 + 1e-9);
        assertTrue(hotScore >= expectedBase / decayWith11 - 1e-9);
    }

    @Test
    void 无互动内容_20分保底不完全沉底() {
        // baseScore=0 → hotScore = 20 / decay，而非 0
        ContentSnapshotVO content = content(0, 0, 0, LocalDateTime.now().minusHours(2));

        double hotScore = HotScoreCalculator.calculate(content);

        double decayWith2 = Math.pow(2 + 2, 1.5);
        double decayWith3 = Math.pow(3 + 2, 1.5);
        assertTrue(hotScore <= 20.0 / decayWith2 + 1e-9);
        assertTrue(hotScore >= 20.0 / decayWith3 - 1e-9);
    }

    @Test
    void 计数与时间null安全_按零计数当前时间处理() {
        // liked/comment/collect 全 null 按 0 → 保底分；createTime null 按当前时间 → decay=(0+2)^1.5 附近
        ContentSnapshotVO content = content(null, null, null, null);

        double hotScore = HotScoreCalculator.calculate(content);

        // 发布即计算：hours=0，decay=2^1.5≈2.828 → 保底分 ≈ 7.07；跨毫秒不会超过 1 小时
        assertTrue(hotScore > 0);
        assertTrue(hotScore <= 20.0 / Math.pow(2, 1.5) + 1e-9);
    }

    @Test
    void 新帖比旧帖同计数热度高() {
        LocalDateTime now = LocalDateTime.now();
        ContentSnapshotVO fresh = content(5, 0, 0, now.minusMinutes(1));
        ContentSnapshotVO stale = content(5, 0, 0, now.minusHours(100));

        // 同计数下时间衰减单调：新帖热度必然更高
        assertTrue(HotScoreCalculator.calculate(fresh) > HotScoreCalculator.calculate(stale));
    }
}
