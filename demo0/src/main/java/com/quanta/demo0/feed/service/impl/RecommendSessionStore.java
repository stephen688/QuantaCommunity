package com.quanta.demo0.feed.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.exception.RecommendSessionExpiredException;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.vo.RecommendSessionSnapshot;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 推荐发现会话的 Redis 状态存储。
 *
 * <p>职责边界：只保存会话快照、页 ID 和构页租约；不查询内容事实、不装配用户化 VO，
 * 不复用定时任务锁。创建、提交和释放分别通过 Redis Lua/SET NX 保证主体限额及 owner
 * 校验，Redis 不可用时直接向上抛错，不伪造空推荐结果。</p>
 */
@Service
@RequiredArgsConstructor
public class RecommendSessionStore {

    /** 会话 ID 由客户端按 UUID v4 生成；Redis 键只接受规范的小写形式。 */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private static final DefaultRedisScript<Long> CREATE_SCRIPT = loadScript(
            "lua/recommend_session_create.lua");
    private static final DefaultRedisScript<Long> COMMIT_SCRIPT = loadScript(
            "lua/recommend_session_commit.lua");
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = loadScript(
            "lua/recommend_session_unlock.lua");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RecommendProperties recommendProperties;

    /**
     * 读取会话并刷新闲置 TTL。
     *
     * @return 活跃快照；主体没有该会话时返回 null
     * @throws RecommendSessionExpiredException 会话墓碑仍在但快照已过期
     */
    public RecommendSessionSnapshot load(RecommendVisitor visitor, String sessionId) {
        String normalizedSessionId = requireSessionId(sessionId);
        String sessionKey = sessionKey(visitor, normalizedSessionId);
        String raw = stringRedisTemplate.opsForValue().get(sessionKey);
        if (raw == null) {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(tombstoneKey(visitor, normalizedSessionId)))) {
                throw new RecommendSessionExpiredException();
            }
            return null;
        }

        RecommendSessionSnapshot snapshot = readSnapshot(raw);
        touch(sessionKey, activeKey(visitor), normalizedSessionId, snapshot);
        return snapshot;
    }

    /**
     * 原子创建一个推荐轮次；同一主体和 sessionId 的并发创建返回同一快照。
     *
     * @return 新建或已存在的活跃快照
     * @throws RecommendSessionExpiredException 首次 sessionId 的旧轮次已过期
     * @throws ContentFailedException 请求缺少必要字段
     * @throws RateLimitExceededException 达到主体活跃会话上限
     */
    public RecommendSessionSnapshot create(
            RecommendVisitor visitor, String sessionId, RecommendQueryDTO query) {
        String normalizedSessionId = requireSessionId(sessionId);
        if (query == null) {
            throw new ContentFailedException("推荐查询参数缺失");
        }
        String revisitOfSessionId = query.getRevisitOfSessionId();
        if (revisitOfSessionId != null && !revisitOfSessionId.isBlank()) {
            revisitOfSessionId = requireSessionId(revisitOfSessionId);
        } else {
            revisitOfSessionId = null;
        }
        int pageSize = query.getPageSize() == null ? 5 : query.getPageSize();
        if (pageSize <= 0 || pageSize > discovery().getMaxPageSize()) {
            throw new ContentFailedException("推荐页面大小超出限制");
        }

        long now = System.currentTimeMillis();
        RecommendSessionSnapshot snapshot = RecommendSessionSnapshot.builder()
                .createdAtMs(now)
                .startedAt(now)
                .upperId(null)
                .category(query.getContentType())
                .pageSize(pageSize)
                .revisitMode(revisitOfSessionId != null)
                .seed(ThreadLocalRandom.current().nextLong())
                .deliveredIds(new HashSet<>())
                .pendingIds(new ArrayDeque<>())
                .recallWindowIndex(-1)
                .revisitOfSessionId(revisitOfSessionId)
                .dbCursorTime(null)
                .dbCursorId(null)
                .dbExhausted(false)
                .totalDelivered(0)
                .pagesByCursor(new HashMap<>())
                .pageNextCursors(new HashMap<>())
                .pageStates(new HashMap<>())
                .pageHasMore(new HashMap<>())
                .pageCanRevisit(new HashMap<>())
                .hadVisibleCandidates(false)
                .currentNextCursor(null)
                .build();

        long sessionTtlMs = sessionMaxTtlMs();
        long tombstoneTtlMs = sessionTtlMs + TimeUnit.MINUTES.toMillis(5L);
        Long result = stringRedisTemplate.execute(
                CREATE_SCRIPT,
                List.of(
                        sessionKey(visitor, normalizedSessionId),
                        activeKey(visitor),
                        tombstoneKey(visitor, normalizedSessionId)),
                Long.toString(now),
                Long.toString(now + Math.min(sessionTtlMs, idleTtlMs())),
                Integer.toString(discovery().getMaxActiveSessions()),
                writeSnapshot(snapshot),
                normalizedSessionId,
                Long.toString(Math.min(sessionTtlMs, idleTtlMs())),
                Long.toString(tombstoneTtlMs),
                Long.toString(tombstoneTtlMs));

        if (result == null) {
            throw new IllegalStateException("推荐会话创建没有 Redis 脚本结果");
        }
        if (Long.valueOf(-1L).equals(result)) {
            throw new RecommendSessionExpiredException();
        }
        if (Long.valueOf(0L).equals(result)) {
            throw new RateLimitExceededException("推荐会话数量已达上限，请稍后重试", 1L);
        }
        if (Long.valueOf(1L).equals(result)) {
            return snapshot;
        }
        // 2 表示并发请求已经成功创建；读取它而不是把本请求的随机 seed 返回出去。
        RecommendSessionSnapshot existing = load(visitor, normalizedSessionId);
        if (existing == null) {
            throw new IllegalStateException("推荐会话创建结果无法读取");
        }
        return existing;
    }

    /**
     * 读取同一轮已经提交的页面。
     *
     * @param pageCursor null 或空串表示首屏；尚未提交的页返回 null
     * @return 页面帖子 ID 的防御性副本
     */
    public List<Long> loadPage(RecommendVisitor visitor, String sessionId, String pageCursor) {
        RecommendSessionSnapshot snapshot = load(visitor, sessionId);
        if (snapshot == null || snapshot.getPagesByCursor() == null) {
            return null;
        }
        List<Long> page = snapshot.getPagesByCursor().get(normalizeCursor(pageCursor));
        return page == null ? null : new ArrayList<>(page);
    }

    /**
     * 获取会话构页租约。
     *
     * @return 是否成功取得当前 owner 的租约
     */
    public boolean tryLock(RecommendVisitor visitor, String sessionId, String owner) {
        if (owner == null || owner.isBlank()) {
            throw new ContentFailedException("推荐构页锁 owner 缺失");
        }
        RecommendSessionSnapshot snapshot = load(visitor, sessionId);
        if (snapshot == null) {
            return false;
        }
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey(visitor, requireSessionId(sessionId)),
                owner,
                RedisConstants.RECOMMEND_V2_SESSION_LOCK_TTL_SECONDS,
                TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 由当前 owner 原子提交完整会话状态；旧 owner 或过期会话不能推进页游标。
     *
     * @return owner 仍持有租约且提交成功
     */
    public boolean commitPage(
            RecommendVisitor visitor,
            String sessionId,
            String owner,
            RecommendSessionSnapshot nextState) {
        if (owner == null || owner.isBlank() || nextState == null) {
            throw new ContentFailedException("推荐构页提交参数缺失");
        }
        String normalizedSessionId = requireSessionId(sessionId);
        long now = System.currentTimeMillis();
        long createdAt = nextState.getCreatedAtMs() == null ? now : nextState.getCreatedAtMs();
        long absoluteExpiresAt = createdAt + sessionMaxTtlMs();
        long activeExpiresAt = now + Math.min(idleTtlMs(), Math.max(0L, absoluteExpiresAt - now));
        Long result = stringRedisTemplate.execute(
                COMMIT_SCRIPT,
                List.of(
                        sessionKey(visitor, normalizedSessionId),
                        lockKey(visitor, normalizedSessionId),
                        activeKey(visitor)),
                owner,
                writeSnapshot(nextState.copy()),
                Long.toString(now),
                Long.toString(absoluteExpiresAt),
                Long.toString(idleTtlMs()),
                Long.toString(activeExpiresAt),
                normalizedSessionId);
        if (Long.valueOf(-1L).equals(result)) {
            throw new RecommendSessionExpiredException();
        }
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 只释放值匹配 owner 的构页租约。
     *
     * @return 当前 owner 是否实际删除了锁
     */
    public boolean unlock(RecommendVisitor visitor, String sessionId, String owner) {
        if (owner == null || owner.isBlank()) {
            return false;
        }
        Long result = stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                List.of(lockKey(visitor, requireSessionId(sessionId))),
                owner);
        return Long.valueOf(1L).equals(result);
    }

    /** 返回会话键，供同包定向测试和曝光服务绑定校验使用。 */
    String sessionKey(RecommendVisitor visitor, String sessionId) {
        return RedisConstants.RECOMMEND_V2_SESSION_KEY_PREFIX
                + visitor.actorKey() + ":" + requireSessionId(sessionId);
    }

    private String activeKey(RecommendVisitor visitor) {
        return RedisConstants.RECOMMEND_V2_SESSION_ACTIVE_KEY_PREFIX + visitor.actorKey();
    }

    private String lockKey(RecommendVisitor visitor, String sessionId) {
        return RedisConstants.RECOMMEND_V2_SESSION_LOCK_KEY_PREFIX
                + visitor.actorKey() + ":" + requireSessionId(sessionId);
    }

    private String tombstoneKey(RecommendVisitor visitor, String sessionId) {
        return RedisConstants.RECOMMEND_V2_SESSION_TOMBSTONE_KEY_PREFIX
                + visitor.actorKey() + ":" + requireSessionId(sessionId);
    }

    private void touch(
            String sessionKey,
            String activeKey,
            String sessionId,
            RecommendSessionSnapshot snapshot) {
        long now = System.currentTimeMillis();
        long createdAt = snapshot.getCreatedAtMs() == null ? now : snapshot.getCreatedAtMs();
        long expiresAt = createdAt + sessionMaxTtlMs();
        long remaining = expiresAt - now;
        if (remaining <= 0) {
            throw new RecommendSessionExpiredException();
        }
        long ttl = Math.min(idleTtlMs(), remaining);
        Boolean touched = stringRedisTemplate.expire(sessionKey, ttl, TimeUnit.MILLISECONDS);
        if (Boolean.FALSE.equals(touched)) {
            throw new RecommendSessionExpiredException();
        }
        stringRedisTemplate.opsForZSet().add(activeKey, sessionId, now + ttl);
        // 活跃索引本身也是可丢弃状态；保留到最长会话再多五分钟，避免索引提前消失导致限额低估。
        stringRedisTemplate.expire(
                activeKey,
                sessionMaxTtlMs() + TimeUnit.MINUTES.toMillis(5L),
                TimeUnit.MILLISECONDS);
    }

    private RecommendSessionSnapshot readSnapshot(String raw) {
        try {
            return objectMapper.readValue(raw, RecommendSessionSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("推荐会话快照无法读取", exception);
        }
    }

    private String writeSnapshot(RecommendSessionSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("推荐会话快照无法保存", exception);
        }
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ContentFailedException("推荐会话标识缺失");
        }
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new ContentFailedException("推荐会话标识格式错误");
        }
        return sessionId;
    }

    private String normalizeCursor(String pageCursor) {
        return pageCursor == null ? "" : pageCursor;
    }

    private RecommendProperties.Discovery discovery() {
        RecommendProperties.Discovery discovery = recommendProperties.getDiscovery();
        if (discovery == null) {
            throw new IllegalStateException("推荐发现配置未装配");
        }
        return discovery;
    }

    private long idleTtlMs() {
        return TimeUnit.MINUTES.toMillis(discovery().getSessionIdleMinutes());
    }

    private long sessionMaxTtlMs() {
        return TimeUnit.MINUTES.toMillis(discovery().getSessionMaxMinutes());
    }

    private static DefaultRedisScript<Long> loadScript(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        try (var input = new ClassPathResource(location).getInputStream()) {
            script.setScriptText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("加载推荐 Redis Lua 失败：" + location, exception);
        }
        script.setResultType(Long.class);
        return script;
    }
}
