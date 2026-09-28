package com.quanta.demo0.feed.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RecommendProperties.Profile 嵌套配置的默认值与校验测试（推荐流个性化 03 Task 3.1）。
 * 断言：默认值与计划逐字一致（召回条数、行为权重、α 参数、衰减参数、任务节奏）；
 * 非法值（alphaMin≥1、decayDailyFactor≤0、权重≤0、召回条数<1）触发 Bean Validation 校验失败，
 * 保证启动期即拦截危险配置；边界值（alphaMin=0、decayDailyFactor=1）合法。
 */
class RecommendPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 默认值与计划逐字一致() {
        RecommendProperties properties = new RecommendProperties();
        RecommendProperties.Profile profile = properties.getProfile();

        // 召回窗口：hot / latest 双池各 150（D6 固定召回窗口）
        assertEquals(150, profile.getRecallHotSize());
        assertEquals(150, profile.getRecallLatestSize());
        // 行为权重：LIKE=2.0 / COLLECT=3.0 / COMMENT=4.0 / VIEW=1.0（D2）
        assertEquals(2.0, profile.getLikeWeight());
        assertEquals(3.0, profile.getCollectWeight());
        assertEquals(4.0, profile.getCommentWeight());
        assertEquals(1.0, profile.getViewWeight());
        // α 过渡参数（D7）
        assertEquals(0.4, profile.getAlphaMin());
        assertEquals(100.0, profile.getBehaviorSaturateThreshold());
        // 衰减参数（D4）
        assertEquals(0.95, profile.getDecayDailyFactor());
        assertEquals(0.5, profile.getDecayMinScore());
        // 任务节奏：浏览对账 10 分钟一轮、衰减每日凌晨 4 点
        assertEquals(600000L, profile.getBrowseSyncFixedDelayMs());
        assertEquals("0 0 4 * * ?", profile.getDecayCron());
        // 默认配置本身必须通过校验
        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void 非法alphaMin超上限_校验失败() {
        RecommendProperties properties = new RecommendProperties();
        properties.getProfile().setAlphaMin(1.2);

        Set<ConstraintViolation<RecommendProperties>> violations = validator.validate(properties);

        // alphaMin 必须 ∈ [0,1)：纯画像权重不允许（否则热度兜底完全失效）
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("alphaMin")));
    }

    @Test
    void 非法衰减因子为零_校验失败() {
        RecommendProperties properties = new RecommendProperties();
        properties.getProfile().setDecayDailyFactor(0.0);

        Set<ConstraintViolation<RecommendProperties>> violations = validator.validate(properties);

        // decayDailyFactor 必须 ∈ (0,1]：0 会让画像一轮清零
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("decayDailyFactor")));
    }

    @Test
    void 非法负行为权重_校验失败() {
        RecommendProperties properties = new RecommendProperties();
        properties.getProfile().setLikeWeight(-1.0);

        Set<ConstraintViolation<RecommendProperties>> violations = validator.validate(properties);

        // 权重必须 > 0：负权重会把画像减成负数，语义不成立
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("likeWeight")));
    }

    @Test
    void 非法召回条数为零_校验失败() {
        RecommendProperties properties = new RecommendProperties();
        properties.getProfile().setRecallHotSize(0);

        Set<ConstraintViolation<RecommendProperties>> violations = validator.validate(properties);

        // 召回条数必须 ≥ 1：0 会导致画像流永远空召回
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("recallHotSize")));
    }

    @Test
    void 边界值alphaMin为零_衰减因子为一_校验通过() {
        RecommendProperties properties = new RecommendProperties();
        properties.getProfile().setAlphaMin(0.0);
        properties.getProfile().setDecayDailyFactor(1.0);

        // alphaMin=0（完全画像化）与 decayDailyFactor=1.0（不衰减）都是合法闭区间端点
        assertTrue(validator.validate(properties).isEmpty());
    }
}
