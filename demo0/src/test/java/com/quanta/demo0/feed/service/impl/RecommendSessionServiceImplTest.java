package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.RecommendExposureService;
import com.quanta.demo0.feed.service.RecommendRerankService;
import com.quanta.demo0.feed.vo.RecommendSessionSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 推荐会话编排：检查页重放元数据、预算耗尽续扫与非固定窗口的耗尽判断。 */
class RecommendSessionServiceImplTest {
    private final RecommendVisitor visitor = RecommendVisitor.from(null, "9845e25a-4e42-4f42-8f71-964ec8a68fb5");
    private final String sessionId = "7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0";

    @Test
    void replayUsesOriginalNextCursorAndRechecksCurrentVisibility() {
        Fixture fixture = new Fixture();
        RecommendSessionSnapshot state = fixture.state();
        state.getPagesByCursor().put("", List.of(1L, 2L));
        state.getPageNextCursors().put("", "original-next");
        state.getPageStates().put("", "READY");
        state.getPageHasMore().put("", true);
        state.getPageCanRevisit().put("", false);
        state.setCurrentNextCursor("later-page-next");
        when(fixture.store.load(visitor, sessionId)).thenReturn(state);
        when(fixture.contents.getContentSnapshots(List.of(1L, 2L))).thenReturn(List.of(content(2)));
        var page = fixture.service.page(visitor, query(null));
        assertThat(page.getNextCursor()).isEqualTo("original-next");
        assertThat(page.getContents()).extracting(ContentSnapshotVO::getContentId).containsExactly(2L);
        verify(fixture.store, never()).commitPage(any(), any(), any(), any());
        verifyNoInteractions(fixture.exposures, fixture.rerank);
    }

    @Test
    void scanBudgetWithoutSourceExhaustionReturnsSearchingInsteadOfFalseEmpty() {
        Fixture fixture = new Fixture();
        fixture.properties.getDiscovery().setScanBudget(2);
        fixture.properties.getDiscovery().setCandidateBatchSize(1);
        RecommendSessionSnapshot state = fixture.state();
        state.setUpperId(9L);
        state.setRecallWindowIndex(3);
        when(fixture.store.load(visitor, sessionId)).thenReturn(state);
        when(fixture.contents.getApprovedRecommendCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(content(9)), List.of(content(8)));
        when(fixture.exposures.findExposed(eq(visitor), anyCollection()))
                .thenAnswer(call -> new LinkedHashSet<>((java.util.Collection<Long>) call.getArgument(1)));
        var page = fixture.service.page(visitor, query(null));
        assertThat(page.getContents()).isEmpty();
        assertThat(page.getRecommendationState()).isEqualTo("SEARCHING");
        assertThat(page.getHasMore()).isTrue();
        assertThat(page.getCanRevisit()).isFalse();
        assertThat(page.getNextCursor()).isNotBlank();
    }

    @Test
    void databaseContinuationFindsUnseenContentOutsideRedisWindow() {
        Fixture fixture = new Fixture();
        RecommendSessionSnapshot state = fixture.state();
        state.setUpperId(1000L);
        state.setRecallWindowIndex(3);
        when(fixture.store.load(visitor, sessionId)).thenReturn(state);
        when(fixture.contents.getApprovedRecommendCandidates(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(content(501), content(500)), List.of());
        when(fixture.exposures.findExposed(eq(visitor), anyCollection())).thenReturn(Set.of(501L));
        when(fixture.contents.getContentSnapshots(anyCollection())).thenAnswer(call -> {
            java.util.Collection<Long> ids = call.getArgument(0);
            return ids.stream().filter(id -> id == 500).map(id -> content(id.intValue())).toList();
        });
        var page = fixture.service.page(visitor, query(null));
        assertThat(page.getContents()).extracting(ContentSnapshotVO::getContentId).containsExactly(500L);
        assertThat(page.getRecommendationState()).isEqualTo("EXHAUSTED");
        assertThat(page.getCanRevisit()).isTrue();
        verify(fixture.exposures, never()).record(any(), any(), any());
    }

    @Test
    void newPageRefillsADeletedSelectionFromRemainingVisibleCandidates() {
        Fixture fixture = new Fixture();
        RecommendSessionSnapshot state = fixture.state();
        state.setDbExhausted(true);
        state.getPendingIds().addAll(List.of(1L, 2L, 3L, 4L, 5L, 6L));
        when(fixture.store.load(visitor, sessionId)).thenReturn(state);
        when(fixture.contents.getContentSnapshots(anyCollection())).thenAnswer(call -> {
            java.util.Collection<Long> ids = call.getArgument(0);
            return ids.stream().filter(id -> ids.size() != 5 || id != 3)
                    .map(id -> content(id.intValue())).toList();
        });
        when(fixture.exposures.findExposed(eq(visitor), anyCollection())).thenReturn(Set.of());
        var page = fixture.service.page(visitor, query(null));
        assertThat(page.getContents()).extracting(ContentSnapshotVO::getContentId)
                .containsExactly(1L, 2L, 4L, 5L, 6L);
        assertThat(page.getRecommendationState()).isEqualTo("EXHAUSTED");
    }

    private RecommendQueryDTO query(String cursor) {
        return RecommendQueryDTO.builder().scene("recommend").feedSessionId(sessionId)
                .pageSize(5).pageCursor(cursor).build();
    }

    private static ContentSnapshotVO content(int id) {
        return ContentSnapshotVO.builder().contentId((long) id).publishUserId((long) id)
                .contentType(1).auditStatus(1).isDeleted(0).createTime(LocalDateTime.now().minusDays(1)).build();
    }

    private class Fixture {
        final RecommendSessionStore store = mock(RecommendSessionStore.class);
        final RecommendExposureService exposures = mock(RecommendExposureService.class);
        final ContentQueryService contents = mock(ContentQueryService.class);
        final RecommendRerankService rerank = mock(RecommendRerankService.class);
        final RecommendDiscoverySelector selector = mock(RecommendDiscoverySelector.class);
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final RecommendProperties properties = new RecommendProperties();
        final RecommendSessionServiceImpl service;

        Fixture() {
            when(store.tryLock(any(), any(), any())).thenReturn(true);
            when(store.commitPage(any(), any(), any(), any())).thenReturn(true);
            ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
            when(redis.opsForZSet()).thenReturn(zsets);
            when(zsets.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
            when(rerank.rankCandidates(any(), anyList())).thenAnswer(call -> call.getArgument(1));
            when(selector.select(any(), anyList(), anyList(), anyInt(), anyInt(), anyLong(), any()))
                    .thenAnswer(call -> {
                        List<ContentSnapshotVO> items = call.getArgument(1);
                        int size = call.getArgument(3);
                        return new ArrayList<>(items.subList(0, Math.min(size, items.size())));
                    });
            service = new RecommendSessionServiceImpl(store, exposures, contents, rerank, selector, redis, properties);
        }

        RecommendSessionSnapshot state() {
            long now = System.currentTimeMillis();
            return RecommendSessionSnapshot.builder().createdAtMs(now).startedAt(now).seed(1L)
                    .pageSize(5).category(null).revisitMode(false).upperId(1000L)
                    .recallWindowIndex(-1).totalDelivered(0).dbExhausted(false).build();
        }
    }
}
