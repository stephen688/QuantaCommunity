package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.service.ContentDetailCacheInvalidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核通过钩子挂载：PENDING->APPROVED 与 REJECTED->APPROVED 都判定 bot mention。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentAuditServiceImplBotMentionTest {

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private ContentMapper contentMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    @InjectMocks
    private CommentAuditServiceImpl service;

    private final QuantabotProperties quantabotProperties = new QuantabotProperties();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "quantabotProperties", quantabotProperties);
    }

    private ContentComment comment(String content, Long replyUserId) {
        return ContentComment.builder()
                .commentId(100L)
                .contentId(10L)
                .parentId(null)
                .replyCommentId(null)
                .replyUserId(replyUserId)
                .userId(3L)
                .content(content)
                .auditStatus(AuditStatus.PENDING.getCode())
                .build();
    }

    private void stubApprovePath(ContentComment comment) {
        when(commentMapper.selectById(100L)).thenReturn(comment);
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), anyInt(), eq(AuditStatus.APPROVED.getCode()),
                isNull(), isNull())).thenReturn(1);
        when(commentMapper.updateCommentCount(eq(10L), anyInt())).thenReturn(1);
        when(commentMapper.selectImagesByCommentId(100L)).thenReturn(List.of());
    }

    @Test
    void 文本命中_审核通过后发事件() {
        stubApprovePath(comment("@框框 这个问题怎么解决", null));

        service.approveComment(100L, null);

        verify(outboxEventService).createBotMentionEvent(
                any(ContentComment.class), eq(List.of()), eq("mentioned"));
        verify(contentDetailCacheInvalidator).evictAfterCommit(10L, "comment-approved");
    }

    @Test
    void 回复bot_审核通过后发replied事件() {
        stubApprovePath(comment("学长说得对", 10000L));

        service.approveComment(100L, null);

        verify(outboxEventService).createBotMentionEvent(
                any(ContentComment.class), eq(List.of()), eq("replied"));
    }

    @Test
    void 未命中_不发事件() {
        stubApprovePath(comment("普通评论", null));

        service.approveComment(100L, null);

        verify(outboxEventService, never()).createBotMentionEvent(
                any(), any(), any());
    }

    @Test
    void 驳回转通过路径同样挂载() {
        ContentComment rejectedComment = comment("@框框 补充一下", null);
        stubApprovePath(rejectedComment);
        when(commentMapper.updateAuditStatusIfCurrent(
                eq(100L), eq(AuditStatus.REJECTED.getCode()),
                eq(AuditStatus.APPROVED.getCode()), isNull(), isNull()))
                .thenReturn(1);

        service.approveRejectedComment(100L, null);

        verify(outboxEventService).createBotMentionEvent(
                any(ContentComment.class), eq(List.of()), eq("mentioned"));
    }
}
