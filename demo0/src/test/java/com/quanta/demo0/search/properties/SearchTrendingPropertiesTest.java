package com.quanta.demo0.search.properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SearchTrendingPropertiesTest {

    @Test
    void rejectsJitterEqualToRedisTtl() {
        SearchTrendingProperties properties = new SearchTrendingProperties();
        properties.setRedisTtlSeconds(60L);
        properties.setRedisTtlJitterSeconds(60L);

        assertThatIllegalStateException()
                .isThrownBy(properties::validateCacheSettings)
                .withMessageContaining("redis-ttl-jitter-seconds");
    }
}
