package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.search.service.TrendingCacheInvalidator;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.answer.service.AnswerCommandService;
import com.quanta.demo0.comment.service.CommentCommandService;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.content.mq.producer.ContentEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.service.impl.ContentCommandServiceImpl;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static com.quanta.demo0.platform.redis.constant.RedisConstants.RECOMMEND_HOT_ALL_KEY;
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
                .when(fixture.contentEventProducer).createFeedDeleteEvent(any(Content.class));

        assertThatThrownBy(() -> fixture.service.deleteContent(42L))
                .isInstanceOf(IllegalStateException.class);

        verify(fixture.invalidator, never()).evictAfterCommit(any());
    }

    private Fixture fixture() {
        ContentCommandServiceImpl service = new ContentCommandServiceImpl();
        ContentMapper contentMapper = mock(ContentMapper.class);
        AnswerCommandService answerCommandService = mock(AnswerCommandService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        ContentVectorSyncService contentVectorSyncService = mock(ContentVectorSyncService.class);
        ContentEventProducer contentEventProducer = mock(ContentEventProducer.class);
        TrendingCacheInvalidator invalidator = mock(TrendingCacheInvalidator.class);
        ContentDetailCacheInvalidator contentDetailCacheInvalidator = mock(ContentDetailCacheInvalidator.class);

        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "answerCommandService", answerCommandService);
        ReflectionTestUtils.setField(service, "commentCommandService", mock(CommentCommandService.class));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "contentVectorSyncService", contentVectorSyncService);
        ReflectionTestUtils.setField(service, "contentEventProducer", contentEventProducer);
        ReflectionTestUtils.setField(service, "searchEventProducer", mock(SearchEventProducer.class));
        ReflectionTestUtils.setField(service, "trendingCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", contentDetailCacheInvalidator);
        ReflectionTestUtils.setField(service, "contentInteractionService", mock(ContentInteractionService.class));
        return new Fixture(service, contentMapper, answerCommandService, redisTemplate,
                contentVectorSyncService, contentEventProducer, invalidator);
    }

    private void whenContentExists(Fixture fixture) {
        when(fixture.contentMapper.selectById(42L)).thenReturn(Content.builder()
                .contentId(42L)
                .contentType(1)
                .publishUserId(7L)
                .build());
        when(fixture.answerCommandService.deleteByQuestionId(42L)).thenReturn(List.of());
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private record Fixture(
            ContentCommandServiceImpl service,
            ContentMapper contentMapper,
            AnswerCommandService answerCommandService,
            StringRedisTemplate redisTemplate,
            ContentVectorSyncService contentVectorSyncService,
            ContentEventProducer contentEventProducer,
            TrendingCacheInvalidator invalidator
    ) {
    }
}
