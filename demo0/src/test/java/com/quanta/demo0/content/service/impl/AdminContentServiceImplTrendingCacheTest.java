package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.dto.ContentAuditDTO;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.feed.service.ContentExposureService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.search.service.impl.TrendingCacheInvalidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminContentServiceImplTrendingCacheTest {

    @Mock
    private ContentMapper contentMapper;
    @Mock
    private ContentVectorSyncService contentVectorSyncService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private ContentExposureService contentExposureService;
    @Mock
    private AdminAuditRecorder adminAuditRecorder;
    @Mock
    private TrendingCacheInvalidator trendingCacheInvalidator;
    @Mock
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    @InjectMocks
    private AdminContentServiceImpl service;

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 2", "2, 1"})
    void auditInvalidatesTrendingAfterSuccessfulVisibilityChange(int oldStatus, int newStatus) {
        when(contentMapper.selectById(7L)).thenReturn(content(7L, oldStatus));

        service.audit(ContentAuditDTO.builder()
                .contentId(7L)
                .auditResult(newStatus)
                .build());

        if (newStatus == 1) {
            verify(contentExposureService).exposeApprovedContent(any(Content.class));
            verify(outboxEventService).createContentTopicTagEvent(7L);
        } else {
            verify(contentExposureService).hideRejectedContent(7L);
        }
        verify(trendingCacheInvalidator).evictAfterCommit("admin-content-audit");
    }

    @Test
    void auditDoesNotInvalidateWhenStatusDoesNotChange() {
        when(contentMapper.selectById(7L)).thenReturn(content(7L, 1));

        service.audit(ContentAuditDTO.builder()
                .contentId(7L)
                .auditResult(1)
                .build());

        verify(trendingCacheInvalidator, never()).evictAfterCommit(any());
        verify(outboxEventService, never()).createContentTopicTagEvent(any());
    }

    @Test
    void auditDoesNotInvalidateWhenDatabaseUpdateFails() {
        when(contentMapper.selectById(7L)).thenReturn(content(7L, 0));
        doThrow(new IllegalStateException("database unavailable"))
                .when(contentMapper).update(any(Content.class));

        assertThatThrownBy(() -> service.audit(ContentAuditDTO.builder()
                .contentId(7L)
                .auditResult(1)
                .build()))
                .isInstanceOf(IllegalStateException.class);

        verify(trendingCacheInvalidator, never()).evictAfterCommit(any());
    }

    @Test
    void deleteInvalidatesTrendingAfterSuccessfulDelete() {
        when(contentMapper.selectById(7L)).thenReturn(content(7L, 1));

        service.deleteContent(7L);

        verify(trendingCacheInvalidator).evictAfterCommit(contains("content-delete"));
    }

    @Test
    void deleteDoesNotInvalidateWhenDatabaseDeleteFails() {
        when(contentMapper.selectById(7L)).thenReturn(content(7L, 1));
        doThrow(new IllegalStateException("database unavailable"))
                .when(contentMapper).softDeleteContent(7L);

        assertThatThrownBy(() -> service.deleteContent(7L))
                .isInstanceOf(IllegalStateException.class);

        verify(trendingCacheInvalidator, never()).evictAfterCommit(any());
    }

    private Content content(Long contentId, Integer auditStatus) {
        Content content = new Content();
        content.setContentId(contentId);
        content.setContentType(1);
        content.setAuditStatus(auditStatus);
        content.setPublishUserId(11L);
        content.setIsDeleted(0);
        return content;
    }
}
