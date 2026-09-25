package com.quanta.demo0.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadPathCachePropertiesTest {

    @Test
    void defaultsMatchReadPathConsistencyBudget() {
        ReadPathCacheProperties properties = new ReadPathCacheProperties();

        assertEquals(10_000L, properties.getAuthentication().getMaximumSize());
        assertEquals(45L, properties.getAuthentication().getTtlSeconds());
        assertEquals(10_000L, properties.getAuthor().getMaximumSize());
        assertEquals(180L, properties.getAuthor().getTtlSeconds());
        assertEquals(10_000L, properties.getContentDetail().getL1MaximumSize());
        assertEquals(30L, properties.getContentDetail().getL1TtlSeconds());
        assertEquals(300L, properties.getContentDetail().getRedisTtlSeconds());
        assertEquals(60L, properties.getContentDetail().getRedisTtlJitterSeconds());
        assertEquals(60L, properties.getContentDetail().getNegativeTtlSeconds());
        assertEquals(360L, properties.getContentDetail().getTombstoneTtlSeconds());
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void rejectsNonPositiveSizesAndTtls() {
        ReadPathCacheProperties properties = new ReadPathCacheProperties();
        properties.getAuthentication().setMaximumSize(0L);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsJitterThatCanProduceNonPositiveRedisTtl() {
        ReadPathCacheProperties properties = new ReadPathCacheProperties();
        properties.getContentDetail().setRedisTtlSeconds(60L);
        properties.getContentDetail().setRedisTtlJitterSeconds(60L);

        assertThrows(IllegalStateException.class, properties::validate);
    }
}
