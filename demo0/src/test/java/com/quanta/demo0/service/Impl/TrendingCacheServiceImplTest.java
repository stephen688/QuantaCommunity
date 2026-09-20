package com.quanta.demo0.service.Impl;

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.properties.SearchTrendingProperties;
import com.quanta.demo0.vo.SearchTrendingVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendingCacheServiceImplTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SearchTrendingProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        properties = new SearchTrendingProperties();
        properties.setL1TtlSeconds(10L);
        properties.setL1MaximumSize(1L);
        properties.setRedisTtlSeconds(300L);
        properties.setRedisTtlJitterSeconds(60L);
    }

    @Test
    void usesL1AfterFirstLoad() {
        when(valueOperations.get("search:trending:all")).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);
        AtomicInteger loads = new AtomicInteger();
        SearchTrendingVO expected = trending("java");

        SearchTrendingVO first = cache.getOrLoad(() -> {
            loads.incrementAndGet();
            return expected;
        });
        SearchTrendingVO second = cache.getOrLoad(() -> {
            loads.incrementAndGet();
            return trending("wrong");
        });

        assertThat(first).isSameAs(expected);
        assertThat(second).isSameAs(expected);
        assertThat(loads).hasValue(1);
        verify(valueOperations, times(1)).get("search:trending:all");
    }

    @Test
    void readsJsonProducedByLegacyFastjsonCache() {
        SearchTrendingVO expected = trending("legacy");
        when(valueOperations.get("search:trending:all")).thenReturn(JSON.toJSONString(expected));
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);

        SearchTrendingVO actual = cache.getOrLoad(() -> trending("database"));

        assertThat(actual).isEqualTo(expected);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void jsonNullFallsBackToSourceAndIsConditionallyReplaced() {
        when(valueOperations.get("search:trending:all")).thenReturn("null");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);
        SearchTrendingVO expected = trending("database");

        SearchTrendingVO actual = cache.getOrLoad(() -> expected);

        assertThat(actual).isSameAs(expected);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("search:trending:all")),
                eq("MATCH"),
                eq("null"),
                any(String.class),
                any(String.class)
        );
    }

    @Test
    void skipsL2WriteWhenRedisReadIsUnavailable() {
        when(valueOperations.get("search:trending:all"))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);
        SearchTrendingVO expected = trending("database");

        SearchTrendingVO actual = cache.getOrLoad(() -> expected);

        assertThat(actual).isSameAs(expected);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void writesMissingValueThroughConditionalScriptWithJitteredTtl() {
        when(valueOperations.get("search:trending:all")).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);

        cache.getOrLoad(() -> trending("database"));

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("search:trending:all")),
                eq("ABSENT"),
                eq(""),
                any(String.class),
                any(String.class)
        );
    }

    @Test
    void tombstoneIsObservedAndCannotBeBlindlyOverwritten() {
        String tombstone = "__INVALIDATED__:27a2d775-3b5f-4d1e-a1ac-2eb3fbfef8d0";
        when(valueOperations.get("search:trending:all")).thenReturn(tombstone);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(0L);
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);
        SearchTrendingVO expected = trending("fresh");

        SearchTrendingVO actual = cache.getOrLoad(() -> expected);

        assertThat(actual).isSameAs(expected);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("search:trending:all")),
                eq("MATCH"),
                eq(tombstone),
                any(String.class),
                any(String.class)
        );
        verify(valueOperations, never()).set(eq("search:trending:all"), any(String.class));
    }

    @Test
    void evictClearsL1AndWritesExpiringTombstone() {
        when(valueOperations.get("search:trending:all")).thenReturn(JSON.toJSONString(trending("old")));
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);
        cache.getOrLoad(() -> trending("unused"));

        cache.evict();

        verify(valueOperations).set(
                eq("search:trending:all"),
                org.mockito.ArgumentMatchers.startsWith("__INVALIDATED__:"),
                eq(360L),
                eq(TimeUnit.SECONDS)
        );
        when(valueOperations.get("search:trending:all"))
                .thenReturn("__INVALIDATED__:after-evict");
        cache.getOrLoad(() -> trending("new"));
        verify(valueOperations, times(2)).get("search:trending:all");
    }

    @Test
    void concurrentMissesUseOneLoaderInsideOneJvm() throws Exception {
        when(valueOperations.get("search:trending:all")).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(1L);
        TrendingCacheServiceImpl cache = new TrendingCacheServiceImpl(redisTemplate, properties);
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<SearchTrendingVO> first = new AtomicReference<>();
        AtomicReference<SearchTrendingVO> second = new AtomicReference<>();

        Runnable firstCall = () -> first.set(cache.getOrLoad(() -> loadOnce(loads, loaderStarted, releaseLoader)));
        Runnable secondCall = () -> second.set(cache.getOrLoad(() -> loadOnce(loads, loaderStarted, releaseLoader)));
        Thread firstThread = new Thread(firstCall);
        Thread secondThread = new Thread(secondCall);

        firstThread.start();
        assertThat(loaderStarted.await(2, TimeUnit.SECONDS)).isTrue();
        secondThread.start();
        releaseLoader.countDown();
        firstThread.join(2000);
        secondThread.join(2000);

        assertThat(loads).hasValue(1);
        assertThat(first.get()).isSameAs(second.get());
    }

    private SearchTrendingVO loadOnce(
            AtomicInteger loads,
            CountDownLatch loaderStarted,
            CountDownLatch releaseLoader
    ) {
        loads.incrementAndGet();
        loaderStarted.countDown();
        try {
            if (!releaseLoader.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("loader release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return trending("single-flight");
    }

    private SearchTrendingVO trending(String keyword) {
        return SearchTrendingVO.builder()
                .hotKeywords(List.of(keyword))
                .hotQuestions(List.of())
                .hotAlumni(List.of())
                .build();
    }
}
