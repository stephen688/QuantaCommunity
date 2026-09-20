package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.Content;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.service.ContentExposureService;
import com.quanta.demo0.service.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentAuditServiceImplTrendingCacheTest {

    @Mock
    private ContentMapper contentMapper;
    @Mock
    private ContentExposureService contentExposureService;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private TrendingCacheInvalidator trendingCacheInvalidator;
    @InjectMocks
    private ContentAuditServiceImpl service;

    @Test
    void successfulApprovalInvalidatesTrendingAfterAuditSideEffects() {
        when(contentMapper.selectById(42L)).thenReturn(pendingContent());
        when(contentMapper.updateAuditStatusIfPending(42L, AuditStatus.APPROVED.getCode()))
                .thenReturn(1);

        service.approveContent(42L);

        verify(trendingCacheInvalidator).evictAfterCommit("content-audit-approved");
        verify(contentExposureService).exposeApprovedContent(any(Content.class));
    }

    @Test
    void unchangedApprovalDoesNotInvalidateTrending() {
        when(contentMapper.selectById(42L)).thenReturn(pendingContent());
        when(contentMapper.updateAuditStatusIfPending(42L, AuditStatus.APPROVED.getCode()))
                .thenReturn(0);

        service.approveContent(42L);

        verify(contentExposureService, never()).exposeApprovedContent(any(Content.class));
        verify(trendingCacheInvalidator, never()).evictAfterCommit(anyString());
    }

    @Test
    void failedAuditSideEffectDoesNotInvalidateTrending() {
        when(contentMapper.selectById(42L)).thenReturn(pendingContent());
        when(contentMapper.updateAuditStatusIfPending(42L, AuditStatus.APPROVED.getCode()))
                .thenReturn(1);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxEventService).createFeedUpsertEvent(any(Content.class));

        assertThatThrownBy(() -> service.approveContent(42L))
                .isInstanceOf(IllegalStateException.class);

        verify(trendingCacheInvalidator).evictAfterCommit("content-audit-approved");
    }

    private Content pendingContent() {
        return Content.builder()
                .contentId(42L)
                .publishUserId(7L)
                .auditStatus(AuditStatus.PENDING.getCode())
                .build();
    }
}
