package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.service.ContentDetailCacheInvalidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static com.quanta.demo0.constant.RedisConstants.RECOMMEND_HOT_ALL_KEY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentServiceImplTrendingCacheTest {

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(7L);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void successfulDeleteEvictsTrendingAfterCommitAndKeepsExistingCleanup() {
        Fixture fixture = fixture();
        whenContentExists(fixture);
        beginTransaction();

        fixture.service.deleteContent(42L);

        verify(fixture.invalidator).evictAfterCommit("content-delete");
        verify(fixture.contentVectorSyncService, never()).deleteByContentId(42L);

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(fixture.contentVectorSyncService).deleteByContentId(42L);
        verify(fixture.redisTemplate.opsForZSet()).remove(RECOMMEND_HOT_ALL_KEY, "42");
    }

    @Test
    void failedDeleteDoesNotEvictTrending() {
        Fixture fixture = fixture();
        when(fixture.contentMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.deleteContent(42L))
                .isInstanceOf(ContentFailedException.class);

        verify(fixture.invalidator, never()).evictAfterCommit(any());
    }

    @Test
    void exceptionalDeleteDoesNotEvictTrending() {
        Fixture fixture = fixture();
        whenContentExists(fixture);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(fixture.outboxEventService).createFeedDeleteEvent(any(Content.class));

        assertThatThrownBy(() -> fixture.service.deleteContent(42L))
                .isInstanceOf(IllegalStateException.class);

        verify(fixture.invalidator, never()).evictAfterCommit(any());
    }

    private Fixture fixture() {
        ContentServiceImpl service = new ContentServiceImpl();
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        ContentVectorSyncService contentVectorSyncService = mock(ContentVectorSyncService.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        TrendingCacheInvalidator invalidator = mock(TrendingCacheInvalidator.class);
        ContentDetailCacheInvalidator contentDetailCacheInvalidator = mock(ContentDetailCacheInvalidator.class);

        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "contentVectorSyncService", contentVectorSyncService);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "trendingCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", contentDetailCacheInvalidator);
        return new Fixture(service, contentMapper, questionMapper, redisTemplate,
                contentVectorSyncService, outboxEventService, invalidator);
    }

    private void whenContentExists(Fixture fixture) {
        when(fixture.contentMapper.selectById(42L)).thenReturn(Content.builder()
                .contentId(42L)
                .contentType(1)
                .publishUserId(7L)
                .build());
        when(fixture.questionMapper.selectAnswersByQuestionId(42L)).thenReturn(List.of());
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private record Fixture(
            ContentServiceImpl service,
            ContentMapper contentMapper,
            QuestionMapper questionMapper,
            StringRedisTemplate redisTemplate,
            ContentVectorSyncService contentVectorSyncService,
            OutboxEventService outboxEventService,
            TrendingCacheInvalidator invalidator
    ) {
    }
}
