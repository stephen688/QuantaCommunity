package com.quanta.demo0.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.enums.ContentDetailState;
import com.quanta.demo0.properties.ReadPathCacheProperties;
import com.quanta.demo0.vo.ContentDetailCacheEntry;
import com.quanta.demo0.vo.ContentDetailSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentDetailCacheServiceImplTest {

    private static final Long CONTENT_ID = 42L;
    private static final String KEY = RedisConstants.CONTENT_DETAIL_KEY + CONTENT_ID;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private ReadPathCacheProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new ReadPathCacheProperties();
        properties.getContentDetail().setL1MaximumSize(100L);
        properties.getContentDetail().setL1TtlSeconds(30L);
        properties.getContentDetail().setRedisTtlSeconds(300L);
        properties.getContentDetail().setRedisTtlJitterSeconds(60L);
        properties.getContentDetail().setNegativeTtlSeconds(60L);
        properties.getContentDetail().setTombstoneTtlSeconds(360L);
    }

    @Test
    void usesL1AfterFirstLoad() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        ContentDetailCacheServiceImpl cache = newCache();
        AtomicInteger loads = new AtomicInteger();
        ContentDetailCacheEntry expected = found("first");

        ContentDetailCacheEntry first = cache.getOrLoad(CONTENT_ID, () -> {
            loads.incrementAndGet();
            return expected;
        });
        ContentDetailCacheEntry second = cache.getOrLoad(CONTENT_ID, () -> {
            loads.incrementAndGet();
            return found("wrong");
        });

        assertThat(first).isSameAs(expected);
        assertThat(second).isSameAs(expected);
        assertThat(loads).hasValue(1);
        verify(valueOperations).get(KEY);
    }

    @Test
    void readsNormalL2JsonAndBackfillsL1() throws Exception {
        ContentDetailCacheEntry expected = found("from-redis");
        when(valueOperations.get(KEY)).thenReturn(json(expected));
        ContentDetailCacheServiceImpl cache = newCache();

        ContentDetailCacheEntry first = cache.getOrLoad(CONTENT_ID,
                () -> found("database"));
        ContentDetailCacheEntry second = cache.getOrLoad(CONTENT_ID,
                () -> found("wrong"));

        assertThat(first).isEqualTo(expected);
        assertThat(second).isEqualTo(expected);
        verify(valueOperations).get(KEY);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void loadsFromSourceOnDoubleMissAndWritesJitteredNormalTtl() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        ContentDetailCacheServiceImpl cache = newCache();
        ContentDetailCacheEntry expected = found("database");

        ContentDetailCacheEntry actual = cache.getOrLoad(CONTENT_ID, () -> expected);

        assertThat(actual).isSameAs(expected);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(KEY)),
                eq("ABSENT"),
                eq(""),
                any(String.class),
                org.mockito.ArgumentMatchers.<String>argThat(ttl -> {
                    long value = Long.parseLong(ttl);
                    return value >= 240 && value <= 360;
                })
        );
    }

    @Test
    void writesNegativeEntryWithShortNegativeTtl() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        ContentDetailCacheServiceImpl cache = newCache();
        ContentDetailCacheEntry expected = new ContentDetailCacheEntry(ContentDetailState.NOT_FOUND, null);

        ContentDetailCacheEntry actual = cache.getOrLoad(CONTENT_ID, () -> expected);

        assertThat(actual).isSameAs(expected);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(KEY)),
                eq("ABSENT"),
                eq(""),
                any(String.class),
                eq("60")
        );
    }

    @Test
    void replacesMalformedJsonUsingObservedValueAndConditionalWrite() {
        when(valueOperations.get(KEY)).thenReturn("{bad-json");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        ContentDetailCacheServiceImpl cache = newCache();
        ContentDetailCacheEntry expected = found("database");

        ContentDetailCacheEntry actual = cache.getOrLoad(CONTENT_ID, () -> expected);

        assertThat(actual).isSameAs(expected);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(KEY)),
                eq("MATCH"),
                eq("{bad-json"),
                any(String.class),
                any(String.class)
        );
    }

    @Test
    void fallsBackToSourceAndOnlyPopulatesL1WhenRedisReadFails() {
        when(valueOperations.get(KEY))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        ContentDetailCacheServiceImpl cache = newCache();
        AtomicInteger loads = new AtomicInteger();
        ContentDetailCacheEntry expected = found("database");

        ContentDetailCacheEntry actual = cache.getOrLoad(CONTENT_ID, () -> {
            loads.incrementAndGet();
            return expected;
        });
        ContentDetailCacheEntry second = cache.getOrLoad(CONTENT_ID, () -> {
            loads.incrementAndGet();
            return found("wrong");
        });

        assertThat(actual).isSameAs(expected);
        assertThat(second).isSameAs(expected);
        assertThat(loads).hasValue(1);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void keepsLoadedValueWhenRedisConditionalWriteFails() {
        when(valueOperations.get(KEY)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        ContentDetailCacheServiceImpl cache = newCache();
        ContentDetailCacheEntry expected = found("database");

        ContentDetailCacheEntry actual = cache.getOrLoad(CONTENT_ID, () -> expected);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void evictClearsL1AndWritesExpiringTombstone() {
        when(valueOperations.get(KEY)).thenReturn(jsonUnchecked(found("old")));
        ContentDetailCacheServiceImpl cache = newCache();
        cache.getOrLoad(CONTENT_ID, () -> found("unused"));

        cache.evict(CONTENT_ID);

        verify(valueOperations).set(
                eq(KEY),
                org.mockito.ArgumentMatchers.startsWith("__INVALIDATED__:"),
                eq(360L),
                eq(TimeUnit.SECONDS)
        );
        when(valueOperations.get(KEY)).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        ContentDetailCacheEntry fresh = found("new");
        assertThat(cache.getOrLoad(CONTENT_ID, () -> fresh)).isSameAs(fresh);
        verify(valueOperations, times(2)).get(KEY);
    }

    @Test
    void recognizesTombstoneAndUsesCompareAndSetInsteadOfBlindSet() {
        String tombstone = "__INVALIDATED__:before-load";
        when(valueOperations.get(KEY)).thenReturn(tombstone);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);
        ContentDetailCacheServiceImpl cache = newCache();
        ContentDetailCacheEntry expected = found("fresh");

        ContentDetailCacheEntry actual = cache.getOrLoad(CONTENT_ID, () -> expected);

        assertThat(actual).isSameAs(expected);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(KEY)),
                eq("MATCH"),
                eq(tombstone),
                any(String.class),
                any(String.class)
        );
        verify(valueOperations, never()).set(eq(KEY), any(String.class), any(Long.class), eq(TimeUnit.SECONDS));
    }

    @Test
    void allowsFreshValueToReplaceUnchangedTombstone() {
        String tombstone = "__INVALIDATED__:same";
        ContentDetailCacheEntry expected = found("fresh");
        when(valueOperations.get(KEY)).thenReturn(tombstone);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        ContentDetailCacheServiceImpl cache = newCache();

        cache.getOrLoad(CONTENT_ID, () -> expected);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(KEY)),
                eq("MATCH"),
                eq(tombstone),
                eq(jsonUnchecked(expected)),
                any(String.class)
        );
    }

    @Test
    void refusesFreshValueWhenTombstoneChangesDuringLoad() throws Exception {
        AtomicReference<String> redisValue = new AtomicReference<>();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        when(valueOperations.get(KEY)).thenAnswer(invocation -> redisValue.get());
        doAnswer(invocation -> {
            redisValue.set((String) invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(eq(KEY), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenAnswer(invocation -> 0L);
        ContentDetailCacheServiceImpl cache = newCache();
        AtomicReference<ContentDetailCacheEntry> result = new AtomicReference<>();
        Thread loaderThread = new Thread(() -> result.set(cache.getOrLoad(CONTENT_ID, () -> {
            loaderStarted.countDown();
            await(releaseLoader);
            return found("stale");
        })));

        loaderThread.start();
        assertThat(loaderStarted.await(2, TimeUnit.SECONDS)).isTrue();
        newCache().evict(CONTENT_ID);
        String newerTombstone = "__INVALIDATED__:newer";
        redisValue.set(newerTombstone);
        releaseLoader.countDown();
        loaderThread.join(2_000);

        assertThat(result.get().snapshot().title()).isEqualTo("stale");
        assertThat(redisValue.get()).isEqualTo(newerTombstone);
        assertThat(redisValue.get()).doesNotContain("stale");
    }

    @Test
    void oldLoaderCannotOverwriteTombstoneWrittenBeforeItCompletes() throws Exception {
        AtomicReference<String> redisValue = new AtomicReference<>();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        when(valueOperations.get(KEY)).thenAnswer(invocation -> redisValue.get());
        doAnswer(invocation -> {
            redisValue.set((String) invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(eq(KEY), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenAnswer(invocation -> 0L);
        ContentDetailCacheServiceImpl cache = newCache();
        Thread loaderThread = new Thread(() -> cache.getOrLoad(CONTENT_ID, () -> {
            loaderStarted.countDown();
            await(releaseLoader);
            return found("old");
        }));

        loaderThread.start();
        assertThat(loaderStarted.await(2, TimeUnit.SECONDS)).isTrue();
        newCache().evict(CONTENT_ID);
        releaseLoader.countDown();
        loaderThread.join(2_000);

        assertThat(redisValue.get()).startsWith("__INVALIDATED__:");
        assertThat(redisValue.get()).doesNotContain("old");
    }

    private ContentDetailCacheServiceImpl newCache() {
        return new ContentDetailCacheServiceImpl(redisTemplate, objectMapper, properties);
    }

    private ContentDetailCacheEntry found(String title) {
        return new ContentDetailCacheEntry(
                ContentDetailState.FOUND,
                new ContentDetailSnapshot(
                        CONTENT_ID,
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

    private String json(ContentDetailCacheEntry entry) throws Exception {
        return objectMapper.writeValueAsString(entry);
    }

    private String jsonUnchecked(ContentDetailCacheEntry entry) {
        try {
            return json(entry);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("loader release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
