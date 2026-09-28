package com.quanta.demo0.content.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 详情缓存 Redis 真栈验收：验证 Caffeine/L2 分层、负结果、墓碑和条件回填边界。
 * 测试只使用临时 Redis，不触碰业务数据库；Redis 故障场景使用独立容器，避免污染共享容器。
 */
@Testcontainers
class ContentDetailCacheRedisIntegrationTests {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2-alpine");
    private static final long CONTENT_ID = 91001L;
    private static final String TOMBSTONE_PREFIX = "__INVALIDATED__:";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private ReadPathCacheProperties properties;

    @BeforeEach
    void setUp() {
        connectionFactory = newConnectionFactory(REDIS);
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        deleteDetailKeys(redisTemplate);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new ReadPathCacheProperties();
        properties.getContentDetail().setL1MaximumSize(100L);
        properties.getContentDetail().setL1TtlSeconds(30L);
        properties.getContentDetail().setRedisTtlSeconds(300L);
        properties.getContentDetail().setRedisTtlJitterSeconds(60L);
        properties.getContentDetail().setNegativeTtlSeconds(60L);
        properties.getContentDetail().setTombstoneTtlSeconds(360L);
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            deleteDetailKeys(redisTemplate);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void foundEntryRoundTripsThroughRedisAndUsesNormalTtl() throws Exception {
        ContentDetailCacheEntry expected = found("redis-roundtrip");

        ContentDetailCacheEntry actual = newCache().getOrLoad(CONTENT_ID, () -> expected);
        String raw = redisTemplate.opsForValue().get(key(CONTENT_ID));

        assertThat(actual).isEqualTo(expected);
        assertThat(raw).isNotNull().doesNotStartWith(TOMBSTONE_PREFIX);
        assertThat(objectMapper.readValue(raw, ContentDetailCacheEntry.class))
                .isEqualTo(expected);
        assertThat(redisTemplate.getExpire(key(CONTENT_ID), TimeUnit.MILLISECONDS))
                .as("FOUND entries must use the configured normal TTL plus or minus jitter")
                .isBetween(239_000L, 360_000L);
    }

    @ParameterizedTest(name = "{0} entry round-trips with the negative TTL")
    @MethodSource("negativeEntries")
    void negativeEntriesRoundTripThroughRedisWithShortTtl(
            ContentDetailState state,
            ContentDetailCacheEntry expected
    ) throws Exception {
        ContentDetailCacheEntry actual = newCache().getOrLoad(CONTENT_ID, () -> expected);
        String raw = redisTemplate.opsForValue().get(key(CONTENT_ID));

        assertThat(actual).isEqualTo(expected);
        assertThat(objectMapper.readValue(raw, ContentDetailCacheEntry.class))
                .isEqualTo(expected);
        assertThat(actual.state()).isEqualTo(state);
        assertThat(redisTemplate.getExpire(key(CONTENT_ID), TimeUnit.MILLISECONDS))
                .as("negative entries must not use the long FOUND TTL")
                .isBetween(55_000L, 60_000L);
    }

    @Test
    void newCacheInstanceReadsL2AndThenServesL1() throws Exception {
        ContentDetailCacheEntry expected = found("l2-hit");
        newCache().getOrLoad(CONTENT_ID, () -> expected);

        AtomicInteger sourceLoads = new AtomicInteger();
        ContentDetailCacheServiceImpl secondInstance = newCache();
        ContentDetailCacheEntry actual = secondInstance.getOrLoad(CONTENT_ID, () -> {
            sourceLoads.incrementAndGet();
            return found("source-must-not-run");
        });
        ContentDetailCacheEntry changedL2Value = found("l2-changed-after-first-hit");
        redisTemplate.opsForValue().set(
                key(CONTENT_ID),
                objectMapper.writeValueAsString(changedL2Value),
                300,
                TimeUnit.SECONDS
        );
        ContentDetailCacheEntry l1Hit = secondInstance.getOrLoad(CONTENT_ID, () -> {
            sourceLoads.incrementAndGet();
            return found("second-source-must-not-run");
        });

        assertThat(actual).isEqualTo(expected);
        assertThat(l1Hit).isEqualTo(expected);
        assertThat(sourceLoads).hasValue(0);
    }

    @Test
    void evictionWritesTombstoneAndUnchangedTombstoneAcceptsFreshSnapshot() throws Exception {
        ContentDetailCacheServiceImpl cache = newCache();
        cache.getOrLoad(CONTENT_ID, () -> found("old"));

        cache.evict(CONTENT_ID);
        String tombstone = redisTemplate.opsForValue().get(key(CONTENT_ID));

        assertThat(tombstone).startsWith(TOMBSTONE_PREFIX);
        assertThat(redisTemplate.getExpire(key(CONTENT_ID), TimeUnit.MILLISECONDS))
                .as("tombstones must retain the configured invalidation TTL")
                .isBetween(359_000L, 360_000L);

        ContentDetailCacheEntry fresh = found("fresh-after-eviction");
        ContentDetailCacheEntry actual = newCache().getOrLoad(CONTENT_ID, () -> fresh);

        assertThat(actual).isEqualTo(fresh);
        assertThat(objectMapper.readValue(
                redisTemplate.opsForValue().get(key(CONTENT_ID)),
                ContentDetailCacheEntry.class
        )).isEqualTo(fresh);
    }

    @Test
    void anotherInstanceRetainsItsL1UntilConfiguredExpiryThenReadsFreshL2() throws Exception {
        // 共享墓碑不广播到远端 L1；缩短配置验证接受的陈旧窗口，不等待生产 30 秒。
        properties.getContentDetail().setL1TtlSeconds(1L);
        ContentDetailCacheServiceImpl firstInstance = newCache();
        ContentDetailCacheServiceImpl secondInstance = newCache();
        ContentDetailCacheEntry old = found("old-on-both-instances");
        ContentDetailCacheEntry fresh = found("fresh-after-commit");
        firstInstance.getOrLoad(CONTENT_ID, () -> old);
        assertThat(secondInstance.getOrLoad(CONTENT_ID, () -> old)).isEqualTo(old);

        firstInstance.evict(CONTENT_ID);
        assertThat(firstInstance.getOrLoad(CONTENT_ID, () -> fresh)).isEqualTo(fresh);
        assertThat(secondInstance.getOrLoad(CONTENT_ID, () -> fresh)).isEqualTo(old);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ContentDetailCacheEntry refreshed;
        do {
            Thread.sleep(20);
            refreshed = secondInstance.getOrLoad(CONTENT_ID, () -> {
                throw new AssertionError("after L1 expiry the fresh real L2 must satisfy the read");
            });
        } while (refreshed.equals(old) && System.nanoTime() < deadline);
        assertThat(refreshed).isEqualTo(fresh);
    }

    @Test
    void corruptedJsonIsReplacedByLoaderValueForNewCacheInstance() throws Exception {
        redisTemplate.opsForValue().set(
                key(CONTENT_ID),
                "{broken-json",
                360,
                TimeUnit.SECONDS
        );
        ContentDetailCacheEntry expected = found("repaired-json");

        ContentDetailCacheEntry actual = newCache().getOrLoad(CONTENT_ID, () -> expected);
        String repairedRaw = redisTemplate.opsForValue().get(key(CONTENT_ID));

        assertThat(actual).isEqualTo(expected);
        assertThat(objectMapper.readValue(repairedRaw, ContentDetailCacheEntry.class))
                .isEqualTo(expected);
    }

    @Test
    void staleLoaderObservedAbsentCannotOverwriteLaterTombstone() throws Exception {
        ContentDetailCacheServiceImpl staleCache = newCache();
        ContentDetailCacheEntry staleSnapshot = found("stale-absent-read");
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicReference<ContentDetailCacheEntry> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread staleLoader = new Thread(() -> {
            try {
                result.set(staleCache.getOrLoad(CONTENT_ID, () -> {
                    loaderStarted.countDown();
                    await(releaseLoader, "stale loader was not released");
                    return staleSnapshot;
                }));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "content-detail-stale-absent-loader");
        staleLoader.start();

        String tombstone;
        try {
            assertThat(loaderStarted.await(10, TimeUnit.SECONDS))
                    .as("loader must pause after observing an absent L2 key")
                    .isTrue();
            newCache().evict(CONTENT_ID);
            tombstone = redisTemplate.opsForValue().get(key(CONTENT_ID));
        } finally {
            releaseLoader.countDown();
            staleLoader.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(staleLoader.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result).hasValue(staleSnapshot);
        assertThat(redisTemplate.opsForValue().get(key(CONTENT_ID)))
                .as("ABSENT compare-and-set must not replace a later tombstone")
                .isEqualTo(tombstone);
    }

    @Test
    void staleLoaderObservedTombstoneCannotOverwriteNewerTombstone() throws Exception {
        newCache().evict(CONTENT_ID);
        String firstTombstone = redisTemplate.opsForValue().get(key(CONTENT_ID));
        ContentDetailCacheServiceImpl staleCache = newCache();
        ContentDetailCacheEntry staleSnapshot = found("stale-tombstone-read");
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicReference<ContentDetailCacheEntry> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread staleLoader = new Thread(() -> {
            try {
                result.set(staleCache.getOrLoad(CONTENT_ID, () -> {
                    loaderStarted.countDown();
                    await(releaseLoader, "stale tombstone loader was not released");
                    return staleSnapshot;
                }));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "content-detail-stale-tombstone-loader");
        staleLoader.start();

        String newerTombstone;
        try {
            assertThat(loaderStarted.await(10, TimeUnit.SECONDS))
                    .as("loader must pause after observing the first tombstone")
                    .isTrue();
            newCache().evict(CONTENT_ID);
            newerTombstone = redisTemplate.opsForValue().get(key(CONTENT_ID));
        } finally {
            releaseLoader.countDown();
            staleLoader.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(staleLoader.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result).hasValue(staleSnapshot);
        assertThat(firstTombstone).startsWith(TOMBSTONE_PREFIX);
        assertThat(newerTombstone).startsWith(TOMBSTONE_PREFIX);
        assertThat(newerTombstone).isNotEqualTo(firstTombstone);
        assertThat(redisTemplate.opsForValue().get(key(CONTENT_ID)))
                .as("MATCH compare-and-set must reject a changed tombstone")
                .isEqualTo(newerTombstone);
    }

    @Test
    void redisPauseFallsBackKeepsL1AndSameInstanceRecoversL2() throws Exception {
        GenericContainer<?> isolatedRedis = newRedisContainer();
        LettuceConnectionFactory unavailableFactory = null;
        boolean paused = false;

        try {
            isolatedRedis.start();
            unavailableFactory = newConnectionFactory(isolatedRedis);
            StringRedisTemplate unavailableTemplate = newTemplate(unavailableFactory);
            ContentDetailCacheServiceImpl cache = new ContentDetailCacheServiceImpl(
                    unavailableTemplate,
                    objectMapper,
                    properties
            );
            DockerClientFactory.instance().client()
                    .pauseContainerCmd(isolatedRedis.getContainerId())
                    .exec();
            paused = true;

            Long outageContentId = CONTENT_ID + 1;
            ContentDetailCacheEntry fallback = found(outageContentId, "redis-down-source");
            AtomicInteger fallbackLoads = new AtomicInteger();
            ContentDetailCacheEntry actual = cache.getOrLoad(outageContentId, () -> {
                fallbackLoads.incrementAndGet();
                return fallback;
            });
            ContentDetailCacheEntry l1HitDuringOutage = cache.getOrLoad(
                    outageContentId,
                    () -> {
                        fallbackLoads.incrementAndGet();
                        return found(outageContentId, "l1-source-must-not-run");
                    }
            );

            assertThat(actual).isEqualTo(fallback);
            assertThat(l1HitDuringOutage).isEqualTo(fallback);
            assertThat(fallbackLoads).hasValue(1);

            cache.evict(outageContentId);
            ContentDetailCacheEntry afterEviction = found(outageContentId, "source-after-outage-eviction");
            ContentDetailCacheEntry refreshed = cache.getOrLoad(outageContentId, () -> {
                fallbackLoads.incrementAndGet();
                return afterEviction;
            });
            assertThat(refreshed).isEqualTo(afterEviction);
            assertThat(fallbackLoads).hasValue(2);

            DockerClientFactory.instance().client()
                    .unpauseContainerCmd(isolatedRedis.getContainerId())
                    .exec();
            paused = false;
            awaitRedis(unavailableTemplate);
            Long recoveredContentId = CONTENT_ID + 2;
            ContentDetailCacheEntry recovered = found(recoveredContentId, "redis-recovered");
            ContentDetailCacheEntry recoveredResult = cache.getOrLoad(recoveredContentId, () -> recovered);

            assertThat(recoveredResult).isEqualTo(recovered);
            assertThat(objectMapper.readValue(
                    unavailableTemplate.opsForValue().get(key(recoveredContentId)),
                    ContentDetailCacheEntry.class
            )).isEqualTo(recovered);
        } finally {
            if (paused && isolatedRedis.getContainerId() != null) {
                DockerClientFactory.instance().client()
                        .unpauseContainerCmd(isolatedRedis.getContainerId())
                        .exec();
            }
            if (unavailableFactory != null) {
                unavailableFactory.destroy();
            }
            stopIfRunning(isolatedRedis);
        }
    }

    private static Stream<Arguments> negativeEntries() {
        return Stream.of(
                Arguments.of(ContentDetailState.NOT_FOUND,
                        new ContentDetailCacheEntry(ContentDetailState.NOT_FOUND, null)),
                Arguments.of(ContentDetailState.DELETED,
                        new ContentDetailCacheEntry(ContentDetailState.DELETED, null)),
                Arguments.of(ContentDetailState.NOT_APPROVED,
                        new ContentDetailCacheEntry(ContentDetailState.NOT_APPROVED, null)),
                Arguments.of(ContentDetailState.INVALID_AUTHOR,
                        new ContentDetailCacheEntry(ContentDetailState.INVALID_AUTHOR, null))
        );
    }

    private ContentDetailCacheServiceImpl newCache() {
        return new ContentDetailCacheServiceImpl(redisTemplate, objectMapper, properties);
    }

    private ContentDetailCacheEntry found(String title) {
        return found(CONTENT_ID, title);
    }

    private ContentDetailCacheEntry found(Long contentId, String title) {
        return new ContentDetailCacheEntry(
                ContentDetailState.FOUND,
                new ContentDetailSnapshot(
                        contentId,
                        2,
                        title,
                        "body",
                        7L,
                        1,
                        LocalDateTime.of(2026, 9, 20, 10, 15),
                        3,
                        4,
                        5,
                        List.of("https://cdn/1.png")
                )
        );
    }

    private static GenericContainer<?> newRedisContainer() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }

    private static LettuceConnectionFactory newConnectionFactory(GenericContainer<?> redis) {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                clientConfiguration
        );
        factory.afterPropertiesSet();
        return factory;
    }

    private static StringRedisTemplate newTemplate(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private static void deleteDetailKeys(StringRedisTemplate template) {
        Set<String> keys = template.keys(RedisConstants.CONTENT_DETAIL_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    private static void stopIfRunning(GenericContainer<?> container) {
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    private static void awaitRedis(StringRedisTemplate template) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                template.execute((RedisCallback<String>) connection -> connection.ping());
                return;
            } catch (Exception exception) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }
        throw new AssertionError("Redis did not recover after unpause");
    }

    private static String key(Long contentId) {
        return RedisConstants.CONTENT_DETAIL_KEY + contentId;
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
