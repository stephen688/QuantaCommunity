package com.quanta.demo0.service.Impl;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.search.properties.SearchTrendingProperties;
import com.quanta.demo0.service.TrendingCacheService;
import com.quanta.demo0.search.vo.SearchTrendingVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 热榜缓存实现类。
 * 流程：
 * 1. 从本地缓存获取热榜数据，若不存在则从L2缓存或数据源加载。
 * 2. 失效热榜缓存时，先从本地缓存失效，再从Redis中失效。
 */
@Service
@Slf4j
public class TrendingCacheServiceImpl implements TrendingCacheService {

    private static final String LOCAL_CACHE_KEY = "trending";
    private static final String TOMBSTONE_PREFIX = "__INVALIDATED__:";
    private static final long TOMBSTONE_TTL_SECONDS = 360L;
    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT;

    static {
        COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>();
        COMPARE_AND_SET_SCRIPT.setLocation(
                new ClassPathResource("lua/trending-cache-compare-set.lua")
        );
        COMPARE_AND_SET_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final SearchTrendingProperties properties;
    private final Cache<String, SearchTrendingVO> localCache;

    public TrendingCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            SearchTrendingProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getL1MaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getL1TtlSeconds()))
                .build();
    }

    // 从本地缓存获取热榜数据，若不存在则从L2缓存或数据源加载
    @Override
    public SearchTrendingVO getOrLoad(Supplier<SearchTrendingVO> loader) {
        return localCache.get(LOCAL_CACHE_KEY, ignored -> loadFromL2OrSource(loader));
    }

    // 失效热榜缓存
    @Override
    public void evict() {
        localCache.invalidate(LOCAL_CACHE_KEY);
        String tombstone = TOMBSTONE_PREFIX + UUID.randomUUID();
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    tombstone,
                    TOMBSTONE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
            log.info("热榜缓存已失效，key={}", RedisConstants.SEARCH_TRENDING_ALL_KEY);
        } catch (Exception exception) {
            log.warn(
                    "热榜 L2 失效失败，等待 TTL 自愈，key={}, stage=tombstone-write",
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    exception
            );
        }
    }
    // 从Redis缓存或数据源加载热榜数据
    private SearchTrendingVO loadFromL2OrSource(Supplier<SearchTrendingVO> loader) {
        String observedRaw;
        try {
            observedRaw = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.SEARCH_TRENDING_ALL_KEY);
        } catch (Exception exception) {
            log.warn(
                    "热榜 L2 读取失败，本次禁止回填 L2，key={}, stage=read",
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    exception
            );
            return loader.get();
        }

        if (observedRaw != null && !observedRaw.startsWith(TOMBSTONE_PREFIX)) {
            try {
                SearchTrendingVO cached = JSON.parseObject(
                        observedRaw,
                        SearchTrendingVO.class
                );
                if (cached != null) {
                    return cached;
                }
                log.warn(
                        "热榜 Redis JSON 为空，回退事实源，key={}, stage=deserialize",
                        RedisConstants.SEARCH_TRENDING_ALL_KEY
                );
            } catch (Exception exception) {
                log.warn(
                        "热榜 Redis JSON 损坏，回退事实源，key={}, stage=deserialize",
                        RedisConstants.SEARCH_TRENDING_ALL_KEY,
                        exception
                );
            }
        }

        SearchTrendingVO loaded = loader.get();
        conditionalWrite(observedRaw, loaded);
        return loaded;
    }
    // 条件回填热榜数据
    private void conditionalWrite(String observedRaw, SearchTrendingVO value) {
        String mode = observedRaw == null ? "ABSENT" : "MATCH";
        String expected = observedRaw == null ? "" : observedRaw;
        String json = JSON.toJSONString(value);
        long ttlSeconds = jitteredRedisTtlSeconds();
        try {
            Long written = stringRedisTemplate.execute(
                    COMPARE_AND_SET_SCRIPT,
                    List.of(RedisConstants.SEARCH_TRENDING_ALL_KEY),
                    mode,
                    expected,
                    json,
                    String.valueOf(ttlSeconds)
            );
            if (!Long.valueOf(1L).equals(written)) {
                log.debug(
                        "热榜 L2 条件回填被拒绝，期间缓存状态已变化，key={}",
                        RedisConstants.SEARCH_TRENDING_ALL_KEY
                );
            }
        } catch (Exception exception) {
            log.warn(
                    "热榜 L2 条件回填失败，key={}, stage=compare-and-set",
                    RedisConstants.SEARCH_TRENDING_ALL_KEY,
                    exception
            );
        }
    }
    // 生成随机TTL, 避免热榜缓存雪崩,意思是当所有请求同时访问热榜时, 会随机分布到不同的时间点，避免失效同时打到数据库。
    private long jitteredRedisTtlSeconds() {
        long base = properties.getRedisTtlSeconds();
        long jitter = properties.getRedisTtlJitterSeconds();
        if (jitter == 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }
}
