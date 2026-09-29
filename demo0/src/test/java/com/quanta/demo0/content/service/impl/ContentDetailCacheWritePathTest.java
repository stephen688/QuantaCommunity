package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.service.Impl.CommentServiceImpl;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.content.dto.ContentAuditDTO;
import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.service.impl.AdminCommentServiceImpl;
import com.quanta.demo0.comment.service.impl.CommentAuditServiceImpl;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.content.enums.ContentDetailState;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.interaction.mapper.ContentInteractionMapper;
import com.quanta.demo0.interaction.service.impl.ContentInteractionServiceImpl;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.platform.redis.properties.ReadPathCacheProperties;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.search.service.impl.TrendingCacheInvalidator;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import com.quanta.demo0.feed.service.ContentExposureService;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.content.vo.ContentDetailCacheEntry;
import com.quanta.demo0.content.vo.ContentDetailSnapshot;
import org.mockito.Answers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 详情缓存写路径验收测试。
 *
 * 覆盖内容发布、互动、删除、后台治理和评论可见性/删除的真实 Service 编排，
 * 只替换数据库、消息和 Redis 等外部边界；提交后的实际失效由生产失效器执行。
 */
class ContentDetailCacheWritePathTest {

    private static final Long CONTENT_ID = 31L;
    private static final Long USER_ID = 9L;

    private RealDetailCacheFixture detailCache;
    private ContentDetailCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        detailCache = new RealDetailCacheFixture();
        invalidator = new ContentDetailCacheInvalidatorImpl(detailCache);
        detailCache.prime(CONTENT_ID);
        BaseContext.setCurrentId(USER_ID);
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
    void publishEvictsDetailOnlyAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        SensitiveWordChecker sensitiveWordChecker = mock(SensitiveWordChecker.class);
        AliyunModerationProperties moderationProperties = mock(AliyunModerationProperties.class);
        ContentCommandServiceImpl service = new ContentCommandServiceImpl();
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "sensitiveWordChecker", sensitiveWordChecker);
        ReflectionTestUtils.setField(service, "moderationProperties", moderationProperties);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);

        when(sensitiveWordChecker.findFirstHit(anyString())).thenReturn(null);
        when(moderationProperties.isEnabled()).thenReturn(false);
        doAnswer(invocation -> {
            Content content = invocation.getArgument(0);
            content.setContentId(CONTENT_ID);
            return null;
        }).when(contentMapper).insert(any(Content.class));

        beginTransaction();
        service.publish(ContentDTO.builder()
                .contentType(1)
                .title("标题")
                .content("正文")
                .build());

        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void publishDatabaseFailureDoesNotRegisterDetailEviction() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        SensitiveWordChecker sensitiveWordChecker = mock(SensitiveWordChecker.class);
        AliyunModerationProperties moderationProperties = mock(AliyunModerationProperties.class);
        ContentCommandServiceImpl service = new ContentCommandServiceImpl();
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "sensitiveWordChecker", sensitiveWordChecker);
        ReflectionTestUtils.setField(service, "moderationProperties", moderationProperties);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);

        when(sensitiveWordChecker.findFirstHit(anyString())).thenReturn(null);
        when(moderationProperties.isEnabled()).thenReturn(false);
        doThrow(new IllegalStateException("database unavailable"))
                .when(contentMapper).insert(any(Content.class));

        assertThrows(IllegalStateException.class, () -> service.publish(ContentDTO.builder()
                .contentType(1)
                .title("标题")
                .content("正文")
                .build()));

        assertOldDetailCached();
    }

    @Test
    void likeSuccessEvictsDetailAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
        ContentInteractionMapper interactionMapper = mock(ContentInteractionMapper.class);
        ContentInteractionServiceImpl service = interactionService(contentMapper, interactionMapper,
                outboxEventService, redisTemplate);

        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID + 1, 0), content(USER_ID + 1, 1));
        when(interactionMapper.insertContentLiked(any())).thenReturn(1);
        when(contentMapper.updateLiked(CONTENT_ID, 1)).thenReturn(true);

        beginTransaction();
        service.likeContent(CONTENT_ID, true);

        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void unlikeSuccessEvictsDetailAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentInteractionMapper interactionMapper = mock(ContentInteractionMapper.class);
        ContentInteractionServiceImpl service = interactionService(contentMapper, interactionMapper,
                outboxEventService, mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));

        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID + 1, 1), content(USER_ID + 1, 0));
        when(interactionMapper.deleteContentLikedByUser(CONTENT_ID, USER_ID)).thenReturn(1);
        when(contentMapper.updateLiked(CONTENT_ID, -1)).thenReturn(true);

        beginTransaction();
        service.likeContent(CONTENT_ID, false);
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void likeCountFailureDoesNotEvictDetail() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentInteractionMapper interactionMapper = mock(ContentInteractionMapper.class);
        ContentInteractionServiceImpl service = interactionService(contentMapper, interactionMapper,
                outboxEventService, mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));

        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID + 1, 0));
        when(interactionMapper.insertContentLiked(any())).thenReturn(1);
        when(contentMapper.updateLiked(CONTENT_ID, 1)).thenReturn(false);

        assertThrows(ContentFailedException.class, () -> service.likeContent(CONTENT_ID, true));

        assertOldDetailCached();
        verify(outboxEventService, never()).createHotScoreRecalculateEvent(any(), anyString());
    }

    @Test
    void likeRollbackDoesNotEvictDetail() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentInteractionMapper interactionMapper = mock(ContentInteractionMapper.class);
        ContentInteractionServiceImpl service = interactionService(contentMapper, interactionMapper,
                outboxEventService, mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));

        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID + 1, 0), content(USER_ID + 1, 1));
        when(interactionMapper.insertContentLiked(any())).thenReturn(1);
        when(contentMapper.updateLiked(CONTENT_ID, 1)).thenReturn(true);

        beginTransaction();
        service.likeContent(CONTENT_ID, true);
        rollback();

        assertOldDetailCached();
    }

    @Test
    void collectSuccessEvictsDetailAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentInteractionMapper interactionMapper = mock(ContentInteractionMapper.class);
        ContentInteractionServiceImpl service = interactionService(contentMapper, interactionMapper,
                outboxEventService, mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));

        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID + 1, 0), content(USER_ID + 1, 0));
        when(interactionMapper.insertCollect(any())).thenReturn(1);
        when(contentMapper.updateCollectCount(CONTENT_ID, 1)).thenReturn(1);

        beginTransaction();
        service.collect(CONTENT_ID, true);
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void uncollectSuccessEvictsDetailAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentInteractionMapper interactionMapper = mock(ContentInteractionMapper.class);
        ContentInteractionServiceImpl service = interactionService(contentMapper, interactionMapper,
                outboxEventService, mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));

        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID + 1, 0), content(USER_ID + 1, 0));
        when(interactionMapper.deleteCollect(CONTENT_ID, USER_ID)).thenReturn(1);
        when(contentMapper.updateCollectCount(CONTENT_ID, -1)).thenReturn(1);

        beginTransaction();
        service.collect(CONTENT_ID, false);
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void collectCountFailureDoesNotEvictDetail() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentInteractionMapper interactionMapper = mock(ContentInteractionMapper.class);
        ContentInteractionServiceImpl service = interactionService(contentMapper, interactionMapper,
                outboxEventService, mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));

        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID + 1, 0));
        when(interactionMapper.insertCollect(any())).thenReturn(1);
        when(contentMapper.updateCollectCount(CONTENT_ID, 1)).thenReturn(0);

        assertThrows(ContentFailedException.class, () -> service.collect(CONTENT_ID, true));

        assertOldDetailCached();
        verify(outboxEventService, never()).createHotScoreRecalculateEvent(any(), anyString());
    }

    @Test
    void userDeleteEvictsDetailAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentVectorSyncService vectorSyncService = mock(ContentVectorSyncService.class);
        ContentCommandServiceImpl service = new ContentCommandServiceImpl();
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "contentVectorSyncService", vectorSyncService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));
        ReflectionTestUtils.setField(service, "trendingCacheInvalidator", mock(TrendingCacheInvalidator.class));
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "contentInteractionService", mock(com.quanta.demo0.interaction.service.ContentInteractionService.class));
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID, 0));

        beginTransaction();
        service.deleteContent(CONTENT_ID);
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void userDeleteFailureDoesNotEvictDetail() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentCommandServiceImpl service = new ContentCommandServiceImpl();
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "contentVectorSyncService", mock(ContentVectorSyncService.class));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));
        ReflectionTestUtils.setField(service, "trendingCacheInvalidator", mock(TrendingCacheInvalidator.class));
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "contentInteractionService", mock(com.quanta.demo0.interaction.service.ContentInteractionService.class));
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID, 0));
        doThrow(new IllegalStateException("database unavailable"))
                .when(contentMapper).softDeleteContent(CONTENT_ID);

        assertThrows(IllegalStateException.class, () -> service.deleteContent(CONTENT_ID));

        assertOldDetailCached();
    }

    @Test
    void adminAuditEvictsDetailAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentExposureService exposureService = mock(ContentExposureService.class);
        AdminContentServiceImpl service = adminContentService(contentMapper, questionMapper, outboxEventService, exposureService);

        Content auditPending = content(USER_ID, 0);
        auditPending.setAuditStatus(AuditStatus.PENDING.getCode());
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(auditPending);
        beginTransaction();
        service.audit(ContentAuditDTO.builder().contentId(CONTENT_ID).auditResult(AuditStatus.APPROVED.getCode()).build());

        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void adminAuditFailureDoesNotEvictDetail() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentExposureService exposureService = mock(ContentExposureService.class);
        AdminContentServiceImpl service = adminContentService(contentMapper, questionMapper, outboxEventService, exposureService);

        Content auditPending = content(USER_ID, 0);
        auditPending.setAuditStatus(AuditStatus.PENDING.getCode());
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(auditPending);
        doThrow(new IllegalStateException("database unavailable"))
                .when(contentMapper).update(any(Content.class));

        assertThrows(IllegalStateException.class, () -> service.audit(ContentAuditDTO.builder()
                .contentId(CONTENT_ID)
                .auditResult(AuditStatus.APPROVED.getCode())
                .build()));

        assertOldDetailCached();
    }

    @Test
    void adminDeleteEvictsDetailAfterCommit() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentExposureService exposureService = mock(ContentExposureService.class);
        AdminContentServiceImpl service = adminContentService(contentMapper, questionMapper, outboxEventService, exposureService);
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID, AuditStatus.APPROVED.getCode()));

        beginTransaction();
        service.deleteContent(CONTENT_ID);
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void adminDeleteFailureDoesNotEvictDetail() {
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        ContentExposureService exposureService = mock(ContentExposureService.class);
        AdminContentServiceImpl service = adminContentService(contentMapper, questionMapper, outboxEventService, exposureService);
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID, AuditStatus.APPROVED.getCode()));
        doThrow(new IllegalStateException("database unavailable"))
                .when(contentMapper).softDeleteContent(CONTENT_ID);

        assertThrows(IllegalStateException.class, () -> service.deleteContent(CONTENT_ID));

        assertOldDetailCached();
    }

    @Test
    void commentApprovalEvictsDetailAfterCommit() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        CommentAuditServiceImpl service = new CommentAuditServiceImpl(
                commentMapper, contentMapper, questionMapper, outboxEventService, invalidator);
        ReflectionTestUtils.setField(service, "quantabotProperties", new QuantabotProperties());
        ContentComment comment = comment(AuditStatus.PENDING.getCode());
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), eq(AuditStatus.PENDING.getCode()), eq(AuditStatus.APPROVED.getCode()), eq(null), eq(null)))
                .thenReturn(1);
        when(commentMapper.updateCommentCount(CONTENT_ID, 1)).thenReturn(1);
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(null);

        beginTransaction();
        assertTrue(service.approveComment(100L, null));
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void commentApprovalCountFailureDoesNotEvictDetail() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        CommentAuditServiceImpl service = new CommentAuditServiceImpl(
                commentMapper, contentMapper, questionMapper, outboxEventService, invalidator);
        ReflectionTestUtils.setField(service, "quantabotProperties", new QuantabotProperties());
        when(commentMapper.selectById(100L)).thenReturn(comment(AuditStatus.PENDING.getCode()));
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), eq(AuditStatus.PENDING.getCode()), eq(AuditStatus.APPROVED.getCode()), eq(null), eq(null)))
                .thenReturn(1);
        when(commentMapper.updateCommentCount(CONTENT_ID, 1)).thenReturn(0);

        assertThrows(RuntimeException.class, () -> service.approveComment(100L, null));

        assertOldDetailCached();
    }

    @Test
    void commentApprovalRollbackDoesNotEvictDetail() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        CommentAuditServiceImpl service = new CommentAuditServiceImpl(
                commentMapper, contentMapper, questionMapper, outboxEventService, invalidator);
        ReflectionTestUtils.setField(service, "quantabotProperties", new QuantabotProperties());
        when(commentMapper.selectById(100L)).thenReturn(comment(AuditStatus.PENDING.getCode()));
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), eq(AuditStatus.PENDING.getCode()), eq(AuditStatus.APPROVED.getCode()), eq(null), eq(null)))
                .thenReturn(1);
        when(commentMapper.updateCommentCount(CONTENT_ID, 1)).thenReturn(1);
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(null);

        beginTransaction();
        service.approveComment(100L, null);
        rollback();

        assertOldDetailCached();
    }

    @Test
    void duplicateCommentApprovalDoesNotEvictDetailOrChangeCount() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        CommentAuditServiceImpl service = new CommentAuditServiceImpl(
                commentMapper, contentMapper, questionMapper, outboxEventService, invalidator);
        ReflectionTestUtils.setField(service, "quantabotProperties", new QuantabotProperties());
        when(commentMapper.selectById(100L)).thenReturn(comment(AuditStatus.APPROVED.getCode()));
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), eq(AuditStatus.PENDING.getCode()), eq(AuditStatus.APPROVED.getCode()), eq(null), eq(null)))
                .thenReturn(0);

        beginTransaction();
        assertFalse(service.approveComment(100L, null));
        assertOldDetailCached();
        commit();

        verify(commentMapper, never()).updateCommentCount(any(), anyInt());
        assertOldDetailCached();
    }

    @Test
    void commentRejectionAfterApprovalDecrementsCountAndEvictsAfterCommit() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        CommentAuditServiceImpl service = new CommentAuditServiceImpl(
                commentMapper, contentMapper, questionMapper, outboxEventService, invalidator);
        ReflectionTestUtils.setField(service, "quantabotProperties", new QuantabotProperties());
        when(commentMapper.selectById(100L)).thenReturn(comment(AuditStatus.APPROVED.getCode()));
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), eq(AuditStatus.APPROVED.getCode()), eq(AuditStatus.REJECTED.getCode()),
                anyString(), eq(null))).thenReturn(1);
        when(commentMapper.updateCommentCount(CONTENT_ID, -1)).thenReturn(1);

        beginTransaction();
        assertTrue(service.revertApprovedComment(100L, "违规", null));
        assertOldDetailCached();
        commit();

        verify(commentMapper).updateCommentCount(CONTENT_ID, -1);
        assertNewDetailLoaded();
    }

    @Test
    void userCommentDeleteEvictsDetailAfterCommit() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        CommentServiceImpl service = new CommentServiceImpl();
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));
        ContentComment comment = ContentComment.builder()
                .commentId(100L)
                .contentId(CONTENT_ID)
                .userId(USER_ID)
                .parentId(50L)
                .build();
        when(commentMapper.selectById(100L)).thenReturn(comment);

        beginTransaction();
        service.deleteComment(100L);
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void userRootCommentDeleteEvictsDetailAfterCommit() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        ContentMapper contentMapper = mock(ContentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        CommentServiceImpl service = new CommentServiceImpl();
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));
        ContentComment comment = ContentComment.builder()
                .commentId(100L)
                .contentId(CONTENT_ID)
                .userId(USER_ID)
                .parentId(null)
                .build();
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentMapper.selectReplyIdsByParentId(100L)).thenReturn(List.of(101L));

        beginTransaction();
        service.deleteComment(100L);
        assertOldDetailCached();
        commit();

        verify(commentMapper).updateCommentCount(CONTENT_ID, -2);
        assertEquals(1, detailCache.evictionCount());
        assertNewDetailLoaded();
    }

    @Test
    void adminCommentDeleteEvictsDetailAfterCommit() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        AdminCommentServiceImpl service = new AdminCommentServiceImpl();
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "adminAuditRecorder", mock(AdminAuditRecorder.class));
        when(commentMapper.selectById(100L)).thenReturn(comment(AuditStatus.APPROVED.getCode()));
        when(commentMapper.selectReplyIdsByParentId(100L)).thenReturn(List.of());

        beginTransaction();
        service.deleteComment(100L);
        assertOldDetailCached();
        commit();

        assertNewDetailLoaded();
    }

    @Test
    void adminCommentDeleteFailureDoesNotEvictDetail() {
        CommentMapper commentMapper = mock(CommentMapper.class);
        QuestionMapper questionMapper = mock(QuestionMapper.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        AdminCommentServiceImpl service = new AdminCommentServiceImpl();
        ReflectionTestUtils.setField(service, "commentMapper", commentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "adminAuditRecorder", mock(AdminAuditRecorder.class));
        when(commentMapper.selectById(100L)).thenReturn(comment(AuditStatus.APPROVED.getCode()));
        when(commentMapper.selectReplyIdsByParentId(100L)).thenReturn(List.of());
        doThrow(new IllegalStateException("database unavailable"))
                .when(commentMapper).softDeleteById(100L);

        assertThrows(IllegalStateException.class, () -> service.deleteComment(100L));

        assertOldDetailCached();
    }

    private ContentInteractionServiceImpl interactionService(
            ContentMapper contentMapper,
            ContentInteractionMapper interactionMapper,
            OutboxEventService outboxEventService,
            StringRedisTemplate redisTemplate
    ) {
        return new ContentInteractionServiceImpl(
                interactionMapper,
                new ContentCounterServiceImpl(contentMapper),
                outboxEventService,
                invalidator,
                redisTemplate
        );
    }

    private AdminContentServiceImpl adminContentService(
            ContentMapper contentMapper,
            QuestionMapper questionMapper,
            OutboxEventService outboxEventService,
            ContentExposureService exposureService
    ) {
        AdminContentServiceImpl service = new AdminContentServiceImpl();
        ReflectionTestUtils.setField(service, "contentMapper", contentMapper);
        ReflectionTestUtils.setField(service, "questionMapper", questionMapper);
        ReflectionTestUtils.setField(service, "outboxEventService", outboxEventService);
        ReflectionTestUtils.setField(service, "contentExposureService", exposureService);
        ReflectionTestUtils.setField(service, "contentVectorSyncService", mock(ContentVectorSyncService.class));
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class, Answers.RETURNS_DEEP_STUBS));
        ReflectionTestUtils.setField(service, "trendingCacheInvalidator", mock(TrendingCacheInvalidator.class));
        ReflectionTestUtils.setField(service, "contentDetailCacheInvalidator", invalidator);
        ReflectionTestUtils.setField(service, "adminAuditRecorder", mock(AdminAuditRecorder.class));
        return service;
    }

    private Content content(Long publishUserId, Integer liked) {
        return Content.builder()
                .contentId(CONTENT_ID)
                .contentType(1)
                .publishUserId(publishUserId)
                .auditStatus(AuditStatus.APPROVED.getCode())
                .liked(liked)
                .collectCount(0)
                .commentCount(0)
                .build();
    }

    private ContentComment comment(Integer auditStatus) {
        return ContentComment.builder()
                .commentId(100L)
                .contentId(CONTENT_ID)
                .userId(USER_ID + 1)
                .content("普通评论")
                .auditStatus(auditStatus)
                .build();
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void commit() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
    }

    private void rollback() {
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private void assertOldDetailCached() {
        assertEquals(detailCache.oldEntry(), detailCache.readWithNewLoader(CONTENT_ID));
    }

    private void assertNewDetailLoaded() {
        assertEquals(detailCache.newEntry(), detailCache.readWithNewLoader(CONTENT_ID));
    }

    /** 真实 Caffeine/详情缓存委托；仅将外围 Redis 交互替换为 mock。 */
    private static final class RealDetailCacheFixture implements ContentDetailCacheService {

        private final ContentDetailCacheService delegate;
        private final ContentDetailCacheEntry oldEntry = entry("旧详情");
        private final ContentDetailCacheEntry newEntry = entry("新详情");
        private int evictionCount;

        private RealDetailCacheFixture() {
            StringRedisTemplate redisTemplate = mock(
                    StringRedisTemplate.class,
                    Answers.RETURNS_DEEP_STUBS
            );
            when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
            delegate = new ContentDetailCacheServiceImpl(
                    redisTemplate,
                    new ObjectMapper().registerModule(new JavaTimeModule()),
                    new ReadPathCacheProperties()
            );
        }

        private static ContentDetailCacheEntry entry(String title) {
            return new ContentDetailCacheEntry(
                    ContentDetailState.FOUND,
                    new ContentDetailSnapshot(
                            CONTENT_ID,
                            1,
                            title,
                            "正文",
                            USER_ID,
                            AuditStatus.APPROVED.getCode(),
                            LocalDateTime.of(2026, 9, 28, 9, 0),
                            1,
                            2,
                            3,
                            List.of()
                    )
            );
        }

        private void prime(Long contentId) {
            delegate.getOrLoad(contentId, () -> oldEntry);
        }

        private ContentDetailCacheEntry readWithNewLoader(Long contentId) {
            return delegate.getOrLoad(contentId, () -> newEntry);
        }

        private ContentDetailCacheEntry oldEntry() {
            return oldEntry;
        }

        private ContentDetailCacheEntry newEntry() {
            return newEntry;
        }

        private int evictionCount() {
            return evictionCount;
        }

        @Override
        public ContentDetailCacheEntry getOrLoad(
                Long contentId,
                Supplier<ContentDetailCacheEntry> loader
        ) {
            return delegate.getOrLoad(contentId, loader);
        }

        @Override
        public void evict(Long contentId) {
            evictionCount++;
            delegate.evict(contentId);
        }
    }
}
