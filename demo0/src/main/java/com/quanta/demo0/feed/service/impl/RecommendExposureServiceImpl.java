package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.exception.RecommendSessionExpiredException;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.RecommendExposureService;
import com.quanta.demo0.feed.vo.RecommendSessionSnapshot;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 推荐真实曝光 Redis 实现。
 *
 * <p>Redis ZSET member 是 contentId，score 是该条曝光独立的失效毫秒时间；Lua 使用
 * ZADD NX，保证重传不刷新 score。会话快照是“已下发”事实边界，曝光接口永远不能
 * 仅凭客户端传入任意 contentId 写入主体曝光集合。</p>
 */
@Service
@RequiredArgsConstructor
public class RecommendExposureServiceImpl implements RecommendExposureService {

    private static final DefaultRedisScript<Long> RECORD_SCRIPT = loadScript(
            "lua/recommend_exposure_record.lua");

    private final StringRedisTemplate stringRedisTemplate;
    private final RecommendSessionStore sessionStore;
    private final RecommendProperties recommendProperties;

    /**
     * 读取主体当前有效曝光；读取前清理 score <= now 的过期成员。
     */
    @Override
    public Set<Long> findExposed(RecommendVisitor visitor, Collection<Long> contentIds) {
        Set<Long> result = new LinkedHashSet<>();
        if (contentIds == null || contentIds.isEmpty()) {
            return result;
        }
        long now = System.currentTimeMillis();
        String key = exposureKey(visitor);
        stringRedisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now);
        List<Long> uniqueIds = new java.util.ArrayList<>(new LinkedHashSet<>(contentIds));
        uniqueIds.forEach(this::requireContentId);
        // 单次 ZMSCORE 批量读取，扩召回不能为每条候选增加一次网络往返。
        List<Double> scores = stringRedisTemplate.opsForZSet().score(
                key, uniqueIds.stream().map(String::valueOf).toArray());
        if (scores == null || scores.size() != uniqueIds.size()) {
            throw new IllegalStateException("推荐曝光查询没有完整 Redis 结果");
        }
        for (int index = 0; index < uniqueIds.size(); index++) {
            Double expiresAt = scores.get(index);
            if (expiresAt != null && expiresAt > now) {
                result.add(uniqueIds.get(index));
            }
        }
        return result;
    }

    /**
     * 校验已下发归属后，以 Lua 原子写入曝光 ZSET。
     */
    @Override
    public void record(
            RecommendVisitor visitor, String feedSessionId, Collection<Long> contentIds) {
        if (feedSessionId == null || feedSessionId.isBlank()) {
            throw new ContentFailedException("推荐会话标识缺失");
        }
        LinkedHashSet<Long> uniqueIds = normalizeIds(contentIds);
        int maxBatch = recommendProperties.getDiscovery().getMaxExposureBatch();
        if (uniqueIds.isEmpty() || uniqueIds.size() > maxBatch) {
            throw new ContentFailedException("推荐曝光批次大小超出限制");
        }

        RecommendSessionSnapshot snapshot = sessionStore.load(visitor, feedSessionId);
        if (snapshot == null) {
            throw new RecommendSessionExpiredException();
        }
        Set<Long> deliveredIds = snapshot.getDeliveredIds();
        if (deliveredIds == null || !deliveredIds.containsAll(uniqueIds)) {
            throw new ContentFailedException("推荐曝光内容不属于当前会话");
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + TimeUnit.HOURS.toMillis(
                recommendProperties.getDiscovery().getExposureHours());
        long keyTtl = TimeUnit.HOURS.toMillis(
                recommendProperties.getDiscovery().getExposureHours() + 1L);
        List<String> arguments = uniqueIds.stream().map(String::valueOf).toList();
        Long inserted = stringRedisTemplate.execute(
                RECORD_SCRIPT,
                List.of(exposureKey(visitor)),
                concatArguments(now, expiresAt, keyTtl, arguments));
        if (inserted == null) {
            throw new IllegalStateException("推荐曝光记录没有 Redis 脚本结果");
        }
    }

    /** 返回新的 v2 真实曝光分区键。 */
    String exposureKey(RecommendVisitor visitor) {
        return RedisConstants.RECOMMEND_V2_EXPOSURE_KEY_PREFIX + visitor.actorKey();
    }

    private Object[] concatArguments(
            long now, long expiresAt, long keyTtl, List<String> contentIds) {
        Object[] arguments = new Object[3 + contentIds.size()];
        arguments[0] = Long.toString(now);
        arguments[1] = Long.toString(expiresAt);
        arguments[2] = Long.toString(keyTtl);
        for (int index = 0; index < contentIds.size(); index++) {
            arguments[index + 3] = contentIds.get(index);
        }
        return arguments;
    }

    private LinkedHashSet<Long> normalizeIds(Collection<Long> contentIds) {
        if (contentIds == null) {
            throw new ContentFailedException("推荐曝光批次缺失");
        }
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (Long contentId : contentIds) {
            requireContentId(contentId);
            uniqueIds.add(contentId);
        }
        return uniqueIds;
    }

    private void requireContentId(Long contentId) {
        if (contentId == null || contentId <= 0) {
            throw new ContentFailedException("推荐曝光内容标识无效");
        }
    }

    private static DefaultRedisScript<Long> loadScript(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        try (var input = new ClassPathResource(location).getInputStream()) {
            script.setScriptText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("加载推荐曝光 Redis Lua 失败：" + location, exception);
        }
        script.setResultType(Long.class);
        return script;
    }
}
