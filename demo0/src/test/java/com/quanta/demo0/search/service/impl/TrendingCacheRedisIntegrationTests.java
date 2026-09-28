package com.quanta.demo0.search.service.impl;

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.search.properties.SearchTrendingProperties;
import com.quanta.demo0.search.vo.SearchTrendingVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TrendingCacheRedisIntegrationTests {

    private static final String CACHE_KEY = "search:trending:all";
    private static final String TOMBSTONE_PREFIX = "__INVALIDATED__:";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SearchTrendingProperties properties;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379)
        );
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(CACHE_KEY);

        properties = new SearchTrendingProperties();
        properties.setL1TtlSeconds(10L);
        properties.setL1MaximumSize(1L);
        properties.setRedisTtlSeconds(300L);
        properties.setRedisTtlJitterSeconds(60L);
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            redisTemplate.delete(CACHE_KEY);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void writesAndReadsNormalJsonWithJitteredTtl() {
        TrendingCacheServiceImpl cache = newCache();
        SearchTrendingVO expected = trending("redis-roundtrip");

        SearchTrendingVO first = cache.getOrLoad(() -> expected);
        String raw = redisTemplate.opsForValue().get(CACHE_KEY);

        assertThat(first).isEqualTo(expected);
        assertThat(raw).isNotNull();
        assertThat(raw).doesNotStartWith(TOMBSTONE_PREFIX);
        assertThat(JSON.parseObject(raw, SearchTrendingVO.class)).isEqualTo(expected);
        assertThat(redisTemplate.getExpire(CACHE_KEY, TimeUnit.MILLISECONDS))
                .as("normal L2 TTL must be 300 seconds plus or minus 60 seconds")
                .isBetween(239_000L, 360_000L);

        TrendingCacheServiceImpl newCacheInstance = newCache();
        SearchTrendingVO second = newCacheInstance.getOrLoad(
                () -> trending("loader-must-not-run-after-l2-hit")
        );

        assertThat(second).isEqualTo(expected);
    }

    @Test
    void evictWritesTombstoneWithTtlCloseTo360Seconds() {
        TrendingCacheServiceImpl cache = newCache();

        cache.evict();

        String tombstone = redisTemplate.opsForValue().get(CACHE_KEY);
        long ttlMillis = redisTemplate.getExpire(CACHE_KEY, TimeUnit.MILLISECONDS);

        assertThat(tombstone).startsWith(TOMBSTONE_PREFIX);
        assertThat(ttlMillis)
                .as("invalidation tombstone TTL must be close to 360 seconds")
                .isBetween(359_000L, 360_000L);
    }

    @Test
    void completedRefillFollowedByEvictionLeavesTombstone() {
        SearchTrendingVO filled = trending("filled-before-eviction");
        newCache().getOrLoad(() -> filled);
        assertThat(JSON.parseObject(
                redisTemplate.opsForValue().get(CACHE_KEY),
                SearchTrendingVO.class
        )).isEqualTo(filled);

        newCache().evict();

        assertThat(redisTemplate.opsForValue().get(CACHE_KEY))
                .startsWith(TOMBSTONE_PREFIX);
    }

    @Test
    void unchangedCorruptedJsonIsReplacedByLoadedValue() {
        redisTemplate.opsForValue().set(CACHE_KEY, "{broken-json", 360, TimeUnit.SECONDS);
        SearchTrendingVO expected = trending("repaired-corrupted-json");

        SearchTrendingVO actual = newCache().getOrLoad(() -> expected);

        assertThat(actual).isEqualTo(expected);
        assertThat(JSON.parseObject(
                redisTemplate.opsForValue().get(CACHE_KEY),
                SearchTrendingVO.class
        )).isEqualTo(expected);
    }

    @Test
    void staleLoaderCannotOverwriteTombstoneAndNewCacheInstanceCanReplaceIt() throws Exception {
        TrendingCacheServiceImpl cache = newCache();
        TrendingCacheServiceImpl invalidatingCacheInstance = newCache();
        SearchTrendingVO staleSnapshot = trending("stale-database-snapshot");
        CountDownLatch loaderReadSnapshot = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicReference<SearchTrendingVO> inFlightResult = new AtomicReference<>();
        AtomicReference<Throwable> inFlightFailure = new AtomicReference<>();

        Thread staleLoaderThread = new Thread(() -> {
            try {
                inFlightResult.set(cache.getOrLoad(() -> {
                    loaderReadSnapshot.countDown();
                    await(releaseLoader, "stale loader was not released");
                    return staleSnapshot;
                }));
            } catch (Throwable throwable) {
                inFlightFailure.set(throwable);
            }
        }, "trending-stale-loader");

        staleLoaderThread.start();
        assertThat(loaderReadSnapshot.await(10, TimeUnit.SECONDS))
                .as("loader must pause after reading the stale database snapshot")
                .isTrue();

        invalidatingCacheInstance.evict();
        String tombstoneAfterEvict = redisTemplate.opsForValue().get(CACHE_KEY);
        assertThat(tombstoneAfterEvict).startsWith(TOMBSTONE_PREFIX);

        releaseLoader.countDown();
        staleLoaderThread.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(staleLoaderThread.isAlive()).isFalse();
        assertThat(inFlightFailure.get()).isNull();
        assertThat(inFlightResult).hasValue(staleSnapshot);
        assertThat(redisTemplate.opsForValue().get(CACHE_KEY))
                .as("the old loader must not overwrite the invalidation tombstone")
                .isEqualTo(tombstoneAfterEvict);

        TrendingCacheServiceImpl newCacheInstance = newCache();
        SearchTrendingVO freshSnapshot = trending("fresh-database-snapshot");
        SearchTrendingVO freshResult = newCacheInstance.getOrLoad(() -> freshSnapshot);

        assertThat(freshResult).isEqualTo(freshSnapshot);
        String replacedRaw = redisTemplate.opsForValue().get(CACHE_KEY);
        assertThat(replacedRaw).doesNotStartWith(TOMBSTONE_PREFIX);
        assertThat(JSON.parseObject(replacedRaw, SearchTrendingVO.class))
                .isEqualTo(freshSnapshot);
    }

    private TrendingCacheServiceImpl newCache() {
        return new TrendingCacheServiceImpl(redisTemplate, properties);
    }

    private SearchTrendingVO trending(String keyword) {
        return SearchTrendingVO.builder()
                .hotKeywords(List.of(keyword))
                .hotQuestions(List.of())
                .hotAlumni(List.of())
                .build();
    }

    private static void await(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(failureMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failureMessage, exception);
        }
    }
}
