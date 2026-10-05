package com.quanta.demo0.feed.service.impl;

import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.UserInterestProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纯候选重排测试：rankCandidates 只能计算排序，不能读写曝光状态。
 */
@ExtendWith(MockitoExtension.class)
class RecommendRerankServiceRankCandidatesTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ContentQueryService contentQueryService;
    @Mock
    private UserInterestProfileService userInterestProfileService;

    private RecommendRerankServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecommendRerankServiceImpl(
                stringRedisTemplate, contentQueryService, userInterestProfileService, new RecommendProperties());
        when(userInterestProfileService.getProfile(7L)).thenReturn(Map.of());
        when(userInterestProfileService.getExplicitProfile(7L)).thenReturn(Map.of());
    }

    @Test
    void rankCandidates只排序不读写Redis曝光() {
        ContentSnapshotVO low = content(1L, 1);
        ContentSnapshotVO high = content(2L, 20);

        List<ContentSnapshotVO> ranked = service.rankCandidates(7L, List.of(low, high));

        assertEquals(List.of(2L, 1L), ranked.stream().map(ContentSnapshotVO::getContentId).toList());
        verify(stringRedisTemplate, never()).opsForSet();
        verify(contentQueryService, never()).getContentFactSnapshots(any());
    }

    private ContentSnapshotVO content(Long id, int liked) {
        return ContentSnapshotVO.builder()
                .contentId(id)
                .contentType(1)
                .likedCount(liked)
                .commentCount(0)
                .collectCount(0)
                .auditStatus(1)
                .isDeleted(0)
                .createTime(LocalDateTime.of(2026, 10, 3, 12, 0))
                .build();
    }
}
