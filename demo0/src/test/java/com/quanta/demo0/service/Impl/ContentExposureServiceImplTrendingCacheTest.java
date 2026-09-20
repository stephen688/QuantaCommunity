package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentExposureServiceImplTrendingCacheTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ContentVectorSyncService contentVectorSyncService;
    @Mock
    private ContentMapper contentMapper;
    @Mock
    private TrendingCacheInvalidator trendingCacheInvalidator;

    @InjectMocks
    private ContentExposureServiceImpl service;

    @Test
    void successfulExposureInvalidatesTrendingAfterExposureSideEffects() {
        Content content = content(42L);

        service.exposeApprovedContent(content);

        verify(trendingCacheInvalidator).evictAfterCommit("content-exposed");
    }

    @Test
    void successfulHideInvalidatesTrendingAfterHideSideEffects() {
        when(contentMapper.selectById(42L)).thenReturn(content(42L));

        service.hideRejectedContent(42L);

        verify(trendingCacheInvalidator).evictAfterCommit("content-hidden");
    }

    @Test
    void failedExposureDoesNotInvalidateTrending() {
        doThrow(new IllegalStateException("vector unavailable"))
                .when(contentVectorSyncService).upsertByContentId(42L);

        service.exposeApprovedContent(content(42L));

        verify(trendingCacheInvalidator, never()).evictAfterCommit(any());
    }

    @Test
    void missingContentDoesNotInvalidateTrendingOnHide() {
        when(contentMapper.selectById(42L)).thenReturn(null);

        service.hideRejectedContent(42L);

        verify(trendingCacheInvalidator, never()).evictAfterCommit(any());
    }

    private Content content(Long contentId) {
        return Content.builder()
                .contentId(contentId)
                .contentType(1)
                .createTime(LocalDateTime.of(2026, 9, 20, 10, 0))
                .liked(1)
                .commentCount(2)
                .collectCount(3)
                .build();
    }
}
