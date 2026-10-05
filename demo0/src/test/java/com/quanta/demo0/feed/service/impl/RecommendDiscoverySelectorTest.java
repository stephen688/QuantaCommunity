package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.UserInterestProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * 推荐流探索选择测试：验证跨页配额、确定性抽样、时间边界和显式厌恶约束。
 */
@ExtendWith(MockitoExtension.class)
class RecommendDiscoverySelectorTest {

    @Mock
    private UserInterestProfileService userInterestProfileService;

    private RecommendDiscoverySelector selector;

    @BeforeEach
    void setUp() {
        RecommendProperties properties = new RecommendProperties();
        properties.getDiscovery().setExplorationRatio(0.2);
        properties.getDiscovery().setRecentDays(7);
        selector = new RecommendDiscoverySelector(userInterestProfileService, properties);
    }

    @Test
    void 每五条最多一个探索位且跨页累计() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 10, 4, 12, 0);
        List<ContentSnapshotVO> ranked = ranked(startedAt, 6);
        List<ContentSnapshotVO> discovery = List.of(
                content(101L, 2, startedAt.minusDays(1)),
                content(102L, 3, startedAt.minusDays(1)));

        List<ContentSnapshotVO> first = selector.select(null, ranked, discovery, 3, 0, 19L, startedAt);
        List<ContentSnapshotVO> second = selector.select(null, ranked.subList(3, 6), discovery,
                3, 3, 19L, startedAt);

        assertEquals(0, first.stream().filter(item -> item.getContentId() >= 100).count());
        assertEquals(1, second.stream().filter(item -> item.getContentId() >= 100).count());
        assertEquals(3, second.size());
        java.util.Set<Long> delivered = new java.util.HashSet<>();
        first.forEach(item -> delivered.add(item.getContentId()));
        second.forEach(item -> assertFalse(delivered.contains(item.getContentId())));
    }

    @Test
    void 固定种子重试得到相同探索顺序并遵守七天边界() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 10, 4, 12, 0);
        List<ContentSnapshotVO> ranked = ranked(startedAt, 5);
        ContentSnapshotVO lower = content(101L, 2, startedAt.minusDays(7));
        ContentSnapshotVO upper = content(102L, 3, startedAt);
        ContentSnapshotVO tooOld = content(103L, 4, startedAt.minusDays(7).minusSeconds(1));

        List<ContentSnapshotVO> first = selector.select(null, ranked, List.of(lower, upper, tooOld),
                5, 0, 42L, startedAt);
        List<ContentSnapshotVO> retry = selector.select(null, ranked, List.of(lower, upper, tooOld),
                5, 0, 42L, startedAt);

        assertEquals(first.stream().map(ContentSnapshotVO::getContentId).toList(),
                retry.stream().map(ContentSnapshotVO::getContentId).toList());
        assertFalse(first.stream().anyMatch(item -> item.getContentId().equals(103L)));
        assertEquals(1, first.stream().filter(item -> item.getContentId() >= 100).count());
    }

    @Test
    void 有非负显式候选时探索不提拔负偏好() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 10, 4, 12, 0);
        ContentSnapshotVO negative = content(101L, 2, startedAt.minusDays(1));
        ContentSnapshotVO neutral = content(102L, 3, startedAt.minusDays(1));
        when(userInterestProfileService.resolveContentTags(negative)).thenReturn(List.of("bad"));
        when(userInterestProfileService.resolveContentTags(neutral)).thenReturn(List.of("good"));
        when(userInterestProfileService.getExplicitProfile(9L))
                .thenReturn(Map.of("bad", -1.0, "good", 0.0));

        List<ContentSnapshotVO> result = selector.select(9L,
                ranked(startedAt, 5), List.of(negative, neutral),
                5, 0, 13L, startedAt);

        assertFalse(result.stream().anyMatch(item -> item.getContentId().equals(101L)));
        assertEquals(1, result.stream().filter(item -> item.getContentId().equals(102L)).count());
    }

    @Test
    void allNegativeExplorationFallsBackToPrimary() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 10, 4, 12, 0);
        ContentSnapshotVO negative = content(101L, 2, startedAt.minusDays(1));
        when(userInterestProfileService.resolveContentTags(negative)).thenReturn(List.of("bad"));
        when(userInterestProfileService.getExplicitProfile(9L)).thenReturn(Map.of("bad", -1.0));
        List<ContentSnapshotVO> primary = ranked(startedAt, 5);
        assertEquals(primary, selector.select(9L, primary, List.of(negative), 5, 0, 13L, startedAt));
    }

    @Test
    void explorationDoesNotMoveAnExistingPrimaryItemIntoTheExplorationSlot() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 10, 4, 12, 0);
        List<ContentSnapshotVO> primary = ranked(startedAt, 5);
        assertEquals(primary, selector.select(null, primary, List.of(primary.get(0)), 5, 0, 13L, startedAt));
    }

    private ContentSnapshotVO content(Long id, long authorId, LocalDateTime createTime) {
        return ContentSnapshotVO.builder()
                .contentId(id)
                .publishUserId(authorId)
                .contentType(1)
                .auditStatus(1)
                .isDeleted(0)
                .createTime(createTime)
                .build();
    }

    private List<ContentSnapshotVO> ranked(LocalDateTime startedAt, int size) {
        return java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(id -> content((long) id, id, startedAt.minusDays(1)))
                .toList();
    }
}
