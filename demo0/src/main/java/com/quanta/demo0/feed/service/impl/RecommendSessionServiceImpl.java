package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.exception.RecommendSessionExpiredException;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.RecommendExposureService;
import com.quanta.demo0.feed.service.RecommendRerankService;
import com.quanta.demo0.feed.service.RecommendSessionService;
import com.quanta.demo0.feed.vo.RecommendSessionPage;
import com.quanta.demo0.feed.vo.RecommendSessionSnapshot;
import com.quanta.demo0.platform.redis.constant.RedisConstants;
import com.quanta.demo0.platform.security.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 推荐发现编排：会话下发去重与真实曝光分离，预算不足时继续查找，数据库读完后才宣布耗尽。
 * 只通过内容域查询契约读取事实；Redis 存 ID 和游标，重放仍检查当前可见性。
 */
@Service
@RequiredArgsConstructor
public class RecommendSessionServiceImpl implements RecommendSessionService {
    private final RecommendSessionStore store;
    private final RecommendExposureService exposures;
    private final ContentQueryService contents;
    private final RecommendRerankService rerank;
    private final RecommendDiscoverySelector selector;
    private final StringRedisTemplate redis;
    private final RecommendProperties properties;

    @Override
    public RecommendSessionPage page(RecommendVisitor visitor, RecommendQueryDTO query) {
        validate(query);
        String sessionId = query.getFeedSessionId();
        String cursor = query.getPageCursor() == null ? "" : query.getPageCursor();
        RecommendSessionSnapshot state = store.load(visitor, sessionId);
        if (state == null) {
            if (!cursor.isEmpty()) throw new RecommendSessionExpiredException();
            validateRevisit(visitor, query);
            state = store.create(visitor, sessionId, query);
        }
        bind(state, query);
        if (state.getPagesByCursor().containsKey(cursor)) return replay(sessionId, cursor, state);
        String owner = UUID.randomUUID().toString();
        if (!store.tryLock(visitor, sessionId, owner)) throw busy();
        try {
            state = store.load(visitor, sessionId);
            if (state == null) throw new RecommendSessionExpiredException();
            bind(state, query);
            if (state.getPagesByCursor().containsKey(cursor)) return replay(sessionId, cursor, state);
            if ((!state.getPagesByCursor().isEmpty() && !Objects.equals(cursor, state.getCurrentNextCursor()))
                    || (state.getPagesByCursor().isEmpty() && !cursor.isEmpty())) {
                throw new RecommendSessionExpiredException();
            }
            state = state.copy();
            RecommendSessionPage result = buildPage(visitor, sessionId, cursor, state);
            if (!store.commitPage(visitor, sessionId, owner, state)) throw busy();
            return result;
        } finally {
            store.unlock(visitor, sessionId, owner);
        }
    }

    private void validate(RecommendQueryDTO query) {
        if (query == null) throw new ContentFailedException("推荐查询参数缺失");
        requireUuid(query.getFeedSessionId());
        if (query.getPageCursor() != null) requireUuid(query.getPageCursor());
        if (query.getRevisitOfSessionId() != null) requireUuid(query.getRevisitOfSessionId());
        if (query.getContentType() != null && query.getContentType() != 1 && query.getContentType() != 2)
            throw new ContentFailedException("内容类型必须为 1 或 2");
        if (query.getPageSize() == null || query.getPageSize() < 1
                || query.getPageSize() > properties.getDiscovery().getMaxPageSize())
            throw new ContentFailedException("推荐页面大小超出限制");
    }

    private void requireUuid(String value) {
        if (value == null || !value.matches("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"))
            throw new ContentFailedException("推荐会话或游标格式错误");
    }

    private void bind(RecommendSessionSnapshot state, RecommendQueryDTO query) {
        if (!Objects.equals(state.getCategory(), query.getContentType())
                || !Objects.equals(state.getPageSize(), query.getPageSize())
                || !Objects.equals(state.getRevisitOfSessionId(), query.getRevisitOfSessionId()))
            throw new ContentFailedException("推荐轮次参数已改变，请刷新");
    }

    private void validateRevisit(RecommendVisitor visitor, RecommendQueryDTO query) {
        String source = query.getRevisitOfSessionId();
        if (source == null) return;
        if (source.equals(query.getFeedSessionId())) throw new ContentFailedException("再看必须创建新轮次");
        RecommendSessionSnapshot old = store.load(visitor, source);
        if (old == null || !Objects.equals(old.getCategory(), query.getContentType())
                || old.getPageStates().entrySet().stream().noneMatch(entry ->
                "EXHAUSTED".equals(entry.getValue()) && Boolean.TRUE.equals(old.getPageCanRevisit().get(entry.getKey()))))
            throw new ContentFailedException("只能再看已经耗尽的同分类轮次");
    }

    private RecommendSessionPage replay(String sessionId, String cursor, RecommendSessionSnapshot state) {
        List<ContentSnapshotVO> page = visibleOrdered(state.getPagesByCursor().get(cursor), state);
        return RecommendSessionPage.builder().contents(page).feedSessionId(sessionId)
                .nextCursor(state.getPageNextCursors().get(cursor))
                .recommendationState(state.getPageStates().get(cursor))
                .hasMore(state.getPageHasMore().get(cursor)).canRevisit(state.getPageCanRevisit().get(cursor)).build();
    }

    private RecommendSessionPage buildPage(RecommendVisitor visitor, String sessionId, String cursor,
                                         RecommendSessionSnapshot state) {
        if (state.getUpperId() == null) {
            Long upper = contents.getApprovedRecommendUpperId(state.getCategory(), time(state.getStartedAt()));
            state.setUpperId(upper == null ? 0L : upper);
            if (state.getUpperId() == 0L) state.setDbExhausted(true);
        }
        int budget = properties.getDiscovery().getScanBudget();
        List<ContentSnapshotVO> eligible = eligible(visitor, state);
        while (eligible.size() < state.getPageSize() * 4 && budget > 0 && !Boolean.TRUE.equals(state.getDbExhausted())) {
            List<Long> ids;
            List<Integer> windows = properties.getDiscovery().getRecallWindowSteps();
            int index = state.getRecallWindowIndex() + 1;
            if (index < windows.size() && windows.get(index) * 2 <= budget) {
                int window = windows.get(index);
                LinkedHashSet<Long> recalled = new LinkedHashSet<>();
                collect(hotKey(state.getCategory()), window, recalled);
                collect(latestKey(state.getCategory()), window, recalled);
                ids = new ArrayList<>(recalled);
                // 两个 ZSET 的读窗口都计入预算，重叠窗口也有真实成本。
                budget -= window * 2;
                state.setRecallWindowIndex(index);
            } else {
                state.setRecallWindowIndex(windows.size() - 1);
                int limit = Math.min(budget, properties.getDiscovery().getCandidateBatchSize());
                List<ContentSnapshotVO> batch = contents.getApprovedRecommendCandidates(state.getCategory(),
                        time(state.getStartedAt()), state.getUpperId(), time(state.getDbCursorTime()), state.getDbCursorId(), limit);
                ids = batch.stream().map(ContentSnapshotVO::getContentId).toList();
                budget -= limit;
                if (!batch.isEmpty()) {
                    ContentSnapshotVO last = batch.get(batch.size() - 1);
                    state.setDbCursorTime(last.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                    state.setDbCursorId(last.getContentId());
                }
                if (batch.size() < limit) state.setDbExhausted(true);
            }
            Set<Long> pending = new HashSet<>(state.getPendingIds());
            for (Long id : ids) if (!state.getDeliveredIds().contains(id) && pending.add(id)) state.getPendingIds().add(id);
            eligible = eligible(visitor, state);
        }
        List<ContentSnapshotVO> selected = List.of();
        List<ContentSnapshotVO> page = List.of();
        // 新页发现删帖时从本次已召回候选补位，重新组合整页，避免重复占用累计探索配额。
        // 每轮至少移除一个失效候选，最多检查本次候选数量，不引入无界查询。
        while (!eligible.isEmpty()) {
            List<ContentSnapshotVO> ranked = rerank.rankCandidates(visitor.userId(), eligible);
            selected = selector.select(visitor.userId(), ranked, eligible,
                    state.getPageSize(), state.getTotalDelivered(), state.getSeed(), time(state.getStartedAt()));
            page = visibleOrdered(selected.stream().map(ContentSnapshotVO::getContentId).toList(), state);
            Set<Long> visibleIds = new HashSet<>(page.stream().map(ContentSnapshotVO::getContentId).toList());
            Set<Long> invalidIds = new HashSet<>(selected.stream().map(ContentSnapshotVO::getContentId).toList());
            invalidIds.removeAll(visibleIds);
            if (invalidIds.isEmpty()) break;
            state.getPendingIds().removeIf(invalidIds::contains);
            eligible = eligible.stream().filter(item -> !invalidIds.contains(item.getContentId())).toList();
        }
        List<Long> ids = page.stream().map(ContentSnapshotVO::getContentId).toList();
        Set<Long> selectedIds = new HashSet<>(selected.stream().map(ContentSnapshotVO::getContentId).toList());
        state.getPendingIds().removeIf(selectedIds::contains);
        state.getDeliveredIds().addAll(ids);
        state.setTotalDelivered(state.getTotalDelivered() + ids.size());
        boolean exhausted = Boolean.TRUE.equals(state.getDbExhausted()) && state.getPendingIds().isEmpty();
        String status = exhausted ? "EXHAUSTED" : page.isEmpty() ? "SEARCHING" : "READY";
        boolean canRevisit = exhausted && Boolean.TRUE.equals(state.getHadVisibleCandidates());
        String next = exhausted ? null : UUID.randomUUID().toString();
        state.getPagesByCursor().put(cursor, new ArrayList<>(ids));
        state.getPageNextCursors().put(cursor, next);
        state.getPageStates().put(cursor, status);
        state.getPageHasMore().put(cursor, !exhausted);
        state.getPageCanRevisit().put(cursor, canRevisit);
        state.setCurrentNextCursor(next);
        return RecommendSessionPage.builder().contents(page).feedSessionId(sessionId).nextCursor(next)
                .recommendationState(status).hasMore(!exhausted).canRevisit(canRevisit).build();
    }

    private List<ContentSnapshotVO> eligible(RecommendVisitor visitor, RecommendSessionSnapshot state) {
        List<ContentSnapshotVO> visible = visibleOrdered(new ArrayList<>(state.getPendingIds()), state);
        if (!visible.isEmpty()) state.setHadVisibleCandidates(true);
        Set<Long> seen = Boolean.TRUE.equals(state.getRevisitMode()) ? Set.of()
                : exposures.findExposed(visitor, visible.stream().map(ContentSnapshotVO::getContentId).toList());
        List<ContentSnapshotVO> result = visible.stream().filter(item -> !seen.contains(item.getContentId())
                && !state.getDeliveredIds().contains(item.getContentId())).toList();
        Set<Long> retained = new HashSet<>(result.stream().map(ContentSnapshotVO::getContentId).toList());
        state.getPendingIds().removeIf(id -> !retained.contains(id));
        return result;
    }

    private List<ContentSnapshotVO> visibleOrdered(List<Long> ids, RecommendSessionSnapshot state) {
        if (ids.isEmpty()) return List.of();
        Map<Long, ContentSnapshotVO> byId = new HashMap<>();
        for (ContentSnapshotVO content : contents.getContentSnapshots(ids)) {
            if (Integer.valueOf(1).equals(content.getAuditStatus()) && Integer.valueOf(0).equals(content.getIsDeleted())
                    && (state.getCategory() == null || Objects.equals(state.getCategory(), content.getContentType()))
                    && (state.getUpperId() == null || content.getContentId() <= state.getUpperId())
                    && content.getCreateTime() != null && !content.getCreateTime().isAfter(time(state.getStartedAt())))
                byId.put(content.getContentId(), content);
        }
        return ids.stream().distinct().map(byId::get).filter(Objects::nonNull).toList();
    }

    private void collect(String key, int size, Set<Long> ids) {
        Set<String> members = redis.opsForZSet().reverseRange(key, 0, size - 1L);
        if (members == null) return;
        for (String member : members) {
            try { long id = Long.parseLong(member); if (id > 0) ids.add(id); }
            catch (NumberFormatException ignored) { /* 非法缓存成员不进入事实查询。 */ }
        }
    }

    private String hotKey(Integer category) {
        return category == null ? RedisConstants.RECOMMEND_HOT_ALL_KEY : category == 1
                ? RedisConstants.RECOMMEND_HOT_LIFE_KEY : RedisConstants.RECOMMEND_HOT_PROFESSIONAL_KEY;
    }

    private String latestKey(Integer category) {
        return category == null ? RedisConstants.RECOMMEND_ALL_KEY : category == 1
                ? RedisConstants.RECOMMEND_LIFE_KEY : RedisConstants.RECOMMEND_PROFESSIONAL_KEY;
    }

    private LocalDateTime time(Long epochMillis) {
        return epochMillis == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private RateLimitExceededException busy() {
        return new RateLimitExceededException("推荐页面正在生成，请稍后重试", 1L);
    }
}
