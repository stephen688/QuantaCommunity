package com.quanta.demo0.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.service.ContentDetailCacheService;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
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
 * 帖子详情两级缓存。Redis 只保存稳定快照和可短暂缓存的负结果，访问者状态不进入缓存。
 */
@Service
@Slf4j
public class ContentDetailCacheServiceImpl implements ContentDetailCacheService {

    private static final String TOMBSTONE_PREFIX = "__INVALIDATED__:";
    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT;

    static {
        COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>();
        COMPARE_AND_SET_SCRIPT.setLocation(
                new ClassPathResource("lua/trending-cache-compare-set.lua")
        );
        COMPARE_AND_SET_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ReadPathCacheProperties properties;
    private final Cache<Long, ContentDetailCacheEntry> localCache;

    public ContentDetailCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            ReadPathCacheProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(properties.getContentDetail().getL1MaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getContentDetail().getL1TtlSeconds()))
                .build();
    }

    @Override
    public ContentDetailCacheEntry getOrLoad(
            Long contentId,
            Supplier<ContentDetailCacheEntry> loader
    ) {
        return localCache.get(contentId, ignored -> loadFromL2OrSource(contentId, loader));
    }

    @Override
    public void evict(Long contentId) {
        localCache.invalidate(contentId);
        String key = key(contentId);
        String tombstone = TOMBSTONE_PREFIX + UUID.randomUUID();
        try {
            stringRedisTemplate.opsForValue().set(
                    key,
                    tombstone,
                    properties.getContentDetail().getTombstoneTtlSeconds(),
                    TimeUnit.SECONDS
            );
            log.info("详情缓存已失效，key={}", key);
        } catch (Exception exception) {
            log.warn(
                    "详情 L2 失效失败，等待 TTL 自愈，key={}, stage=tombstone-write",
                    key,
                    exception
            );
        }
    }

    private ContentDetailCacheEntry loadFromL2OrSource(
            Long contentId,
            Supplier<ContentDetailCacheEntry> loader
    ) {
        String key = key(contentId);
        String observedRaw;
        try {
            observedRaw = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception exception) {
            log.warn(
                    "详情 L2 读取失败，本次禁止回填 L2，key={}, stage=read",
                    key,
                    exception
            );
            return loader.get();
        }

        if (observedRaw != null && !observedRaw.startsWith(TOMBSTONE_PREFIX)) {
            try {
                ContentDetailCacheEntry cached = objectMapper.readValue(
                        observedRaw,
                        ContentDetailCacheEntry.class
                );
                if (cached != null && cached.state() != null) {
                    return cached;
                }
                log.warn("详情 L2 JSON 为空，回退事实源，key={}, stage=deserialize", key);
            } catch (Exception exception) {
                log.warn(
                        "详情 L2 JSON 损坏，回退事实源，key={}, stage=deserialize",
                        key,
                        exception
                );
            }
        }

        ContentDetailCacheEntry loaded = loader.get();
        conditionalWrite(key, observedRaw, loaded);
        return loaded;
    }

    private void conditionalWrite(
            String key,
            String observedRaw,
            ContentDetailCacheEntry value
    ) {
        String mode = observedRaw == null ? "ABSENT" : "MATCH";
        String expected = observedRaw == null ? "" : observedRaw;
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            log.warn(
                    "详情缓存序列化失败，跳过 L2 回填，key={}, stage=serialize",
                    key,
                    exception
            );
            return;
        }

        long ttlSeconds = ttlSeconds(value);
        try {
            Long written = stringRedisTemplate.execute(
                    COMPARE_AND_SET_SCRIPT,
                    List.of(key),
                    mode,
                    expected,
                    json,
                    String.valueOf(ttlSeconds)
            );
            if (!Long.valueOf(1L).equals(written)) {
                log.debug("详情 L2 条件回填被拒绝，期间缓存状态已变化，key={}", key);
            }
        } catch (Exception exception) {
            log.warn(
                    "详情 L2 条件回填失败，key={}, stage=compare-and-set",
                    key,
                    exception
            );
        }
    }

    private long ttlSeconds(ContentDetailCacheEntry value) {
        if (value == null || value.state() != ContentDetailState.FOUND) {
            return properties.getContentDetail().getNegativeTtlSeconds();
        }
        long base = properties.getContentDetail().getRedisTtlSeconds();
        long jitter = properties.getContentDetail().getRedisTtlJitterSeconds();
        if (jitter == 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }

    private String key(Long contentId) {
        return RedisConstants.CONTENT_DETAIL_KEY + contentId;
    }
}
