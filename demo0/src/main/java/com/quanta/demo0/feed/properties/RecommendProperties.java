package com.quanta.demo0.feed.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

/**
 * 推荐流配置：启动时 Redis 冷启动预热、画像流重排参数（召回窗口、行为权重、α 过渡、衰减节奏）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quanta.recommend")
@Validated
public class RecommendProperties {

    /**
     * 启动时若 Redis 推荐 ZSET 为空，则从 MySQL 已通过内容预热。
     */
    private boolean warmupOnStartup = true;

    @Min(1)
    private int warmupBatchSize = 100;

    /**
     * 画像流个性化配置（推荐流个性化 D2/D4/D6/D7）。
     * 唯一真源：消费者权重表、衰减任务因子、重排召回与 α 参数均从本配置注入，
     * 严禁在业务代码里另留常量副本形成双真源。
     */
    @Valid
    private Profile profile = new Profile();

    /**
     * 画像流参数集合。
     * 校验规则：行为权重 > 0；alphaMin ∈ [0,1)；decayDailyFactor ∈ (0,1]；
     * 召回条数 ≥ 1；饱和阈值 > 0（α 公式分母，0 会除零）。
     */
    @Data
    public static class Profile {

        /** 明确喜好独立增益，不计入行为 __total 或匹配分分母。 */
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax("2.0")
        private double explicitPositiveWeight = 0.75;

        /** 明确厌恶的惩罚强于喜好增益，仅降权不剔除候选。 */
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax("3.0")
        private double explicitNegativeWeight = 1.25;

        /** 画像流 hot 池召回条数（ZREVRANGE top N） */
        @Min(1)
        private int recallHotSize = 150;

        /** 画像流 latest 池召回条数（新帖冷启动保底） */
        @Min(1)
        private int recallLatestSize = 150;

        /** 点赞行为权重（画像累加换算） */
        @DecimalMin(value = "0.0", inclusive = false)
        private double likeWeight = 2.0;

        /** 收藏行为权重（画像累加换算） */
        @DecimalMin(value = "0.0", inclusive = false)
        private double collectWeight = 3.0;

        /** 评论行为权重（画像累加换算） */
        @DecimalMin(value = "0.0", inclusive = false)
        private double commentWeight = 4.0;

        /** 浏览行为权重（画像累加换算） */
        @DecimalMin(value = "0.0", inclusive = false)
        private double viewWeight = 1.0;

        /** 老用户热度分下限权重：α = 1 - S·(1-alphaMin)，S 为行为饱和度 */
        @DecimalMin("0.0")
        @DecimalMax(value = "1.0", inclusive = false)
        private double alphaMin = 0.4;

        /** 累计行为权重饱和阈值（α 过渡用：__total 达到该值视为完全个性化） */
        @DecimalMin(value = "0.0", inclusive = false)
        private double behaviorSaturateThreshold = 100.0;

        /** 每日衰减系数：画像 field × 该因子 */
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax("1.0")
        private double decayDailyFactor = 0.95;

        /** 低于此值的画像 field 视为陈旧兴趣，衰减时删除 */
        private double decayMinScore = 0.5;

        /** 浏览对账任务轮询间隔（毫秒） */
        @Min(1)
        private long browseSyncFixedDelayMs = 600000L;

        /** 画像衰减任务 cron（默认每日凌晨 4 点） */
        private String decayCron = "0 0 4 * * ?";
    }
}
