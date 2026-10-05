package com.quanta.demo0.feed.properties;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 推荐发现配置：验证受控默认值及交叉字段边界，不启动外部依赖。 */
class RecommendDiscoveryPropertiesTest {
    @Test
    void discoveryDefaultsAndBoundsAreValidated() throws Exception {
        RecommendProperties properties = new RecommendProperties();
        Object discovery = RecommendProperties.class.getMethod("getDiscovery").invoke(properties);
        assertThat(discovery.getClass().getMethod("getExplorationRatio").invoke(discovery)).isEqualTo(0.2);
        assertThat(discovery.getClass().getMethod("getRecentDays").invoke(discovery)).isEqualTo(7);
        assertThat(discovery.getClass().getMethod("getExposureHours").invoke(discovery)).isEqualTo(24);
        discovery.getClass().getMethod("setSessionIdleMinutes", int.class).invoke(discovery, 121);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }
}
