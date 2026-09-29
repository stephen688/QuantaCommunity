package com.quanta.demo0.comment.service.impl;

import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.answer.service.AnswerQueryService;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.comment.mapper.CommentMapper;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.comment.service.bot.BotMentionDetector;
import com.quanta.demo0.comment.service.CommentAuditService;
import com.quanta.demo0.comment.service.CommentCounterService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.comment.mq.producer.CommentEventProducer;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 评论审核服务实现类。
 *
 * 核心职责：
 * 1. 处理评论审核通过/驳回，维护审核状态与拒绝原因；
 * 2. 在审核通过后补执行计数、热度、搜索索引、通知等副作用；
 * 3. 与评论发布链路解耦，将“可见后生效”的逻辑集中到审核阶段。
 *
 * 设计说明：
 * - 通过事务与 afterCommit 保证主数据提交后再执行外部副作用；
 * - 仅允许待审状态自动流转，避免重复审核与状态回滚冲突。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentAuditServiceImpl implements CommentAuditService {

    private final CommentMapper commentMapper;
    private final ContentQueryService contentQueryService;
    private final AnswerQueryService answerQueryService;
    private final FeedEventProducer feedEventProducer;
    private final SearchEventProducer searchEventProducer;
    private final CommentEventProducer commentEventProducer;
    private final NotificationEventProducer notificationEventProducer;
    private final ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    /** 评论可见性变化统一通过评论计数服务同步更新帖子/回答计数。 */
    private final CommentCounterService commentCounterService;

    /** bot 账号与昵称配置；审核服务只消费配置，不持有 HTTP 上下文。 */
    @Autowired
    private QuantabotProperties quantabotProperties;

    @Transactional
    @Override
    public boolean approveComment(Long commentId) {
        return approveComment(commentId, null);
    }

    @Override
    @Transactional
    public boolean approveComment(Long commentId, Long auditUserId) {
        ContentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            log.warn("评论自动通过失败，评论不存在 commentId={}", commentId);
            return false;
        }

        // 只有 PENDING 还能改成 APPROVED；AI 和管理员并发时只会有一个成功。
        int updatedRows = commentMapper.updateAuditStatusIfCurrent(commentId, AuditStatus.PENDING.getCode(), AuditStatus.APPROVED.getCode(), null, auditUserId);
        if (updatedRows != 1) {
            log.info("评论已非待审，跳过自动通过 commentId={}", commentId);
            return false;
        }

        incrementCommentCounts(comment);
        contentDetailCacheInvalidator.evictAfterCommit(comment.getContentId(), "comment-approved");

        // 评论可见后产生的通知先写 Outbox，与状态和计数一起提交。
        createCommentNotificationEvents(comment);


        // 评论状态、帖子评论数和热度 Outbox 一起提交。
        feedEventProducer.createHotScoreRecalculateEvent(comment.getContentId(), "COMMENT_ADD");

        createCommentSearchEvents(comment, "COMMENT_ADD");
        // C-1/C-4：评论可见后再判定 bot mention，并将触发事件写入同一事务。
        maybeCreateBotMentionEvent(comment);
        return true;
    }

    @Override
    @Transactional
    public boolean rejectComment(Long commentId, String rejectReason) {
        return rejectComment(commentId, rejectReason, null);
    }

    @Override
    @Transactional
    public boolean rejectComment(Long commentId, String rejectReason, Long auditUserId) {
        ContentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            log.warn("评论驳回失败，评论不存在 commentId={}", commentId);
            return false;
        }

        // 驳回原因和状态由同一条条件 SQL 更新，避免审核结果被覆盖。
        int updatedRows = commentMapper.updateAuditStatusIfCurrent(commentId, AuditStatus.PENDING.getCode(), AuditStatus.REJECTED.getCode(), rejectReason, auditUserId);
        if (updatedRows != 1) {
            log.info("评论已非待审，跳过驳回 commentId={}", commentId);
            return false;
        }
        return true;
    }

    @Transactional
    @Override
    public boolean approveRejectedComment(Long commentId, Long auditUserId) {
        ContentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new CommentFailedException("评论不存在");
        }

        int updatedRows = commentMapper.updateAuditStatusIfCurrent(commentId, AuditStatus.REJECTED.getCode(), AuditStatus.APPROVED.getCode(), null, auditUserId);
        if (updatedRows != 1) {
            return false;
        }

        incrementCommentCounts(comment);
        contentDetailCacheInvalidator.evictAfterCommit(comment.getContentId(), "comment-approved");
        createCommentNotificationEvents(comment);

        // 驳回评论重新通过后，评论数和热度 Outbox 一起提交。
        feedEventProducer.createHotScoreRecalculateEvent(comment.getContentId(), "COMMENT_ADD");
        createCommentSearchEvents(comment, "COMMENT_ADD");
        // C-1/C-4：驳回评论重新通过也必须进入同一 bot 触发判定。
        maybeCreateBotMentionEvent(comment);
        return true;
    }

    @Transactional
    @Override
    public boolean revertApprovedComment(Long commentId, String rejectReason, Long auditUserId) {
        ContentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new CommentFailedException("评论不存在");
        }

        // 先抢到状态变更权，再回滚计数；失败时不允许误减评论数。
        int updatedRows = commentMapper.updateAuditStatusIfCurrent(commentId, AuditStatus.APPROVED.getCode(), AuditStatus.REJECTED.getCode(), rejectReason, auditUserId);
        if (updatedRows != 1) {
            return false;
        }

        decrementCommentCounts(comment);
        contentDetailCacheInvalidator.evictAfterCommit(comment.getContentId(), "comment-reverted");

        // 评论数减少和热度 Outbox 必须在同一个事务中提交。
        feedEventProducer.createHotScoreRecalculateEvent(comment.getContentId(), "COMMENT_DELETE");
        createCommentSearchEvents(comment, "COMMENT_DELETE");
        return true;
    }

    private void incrementCommentCounts(ContentComment comment) {
        int rows = commentCounterService.changeCommentCount(comment.getContentId(), 1);
        if (rows != 1) {
            throw new CommentFailedException("更新内容表评论数失败");
        }
        if (comment.getAnswerId() != null) {
            int rows2 = commentCounterService.changeAnswerCommentCount(comment.getAnswerId(), 1);
            if (rows2 != 1) {
                throw new CommentFailedException("更新回答表评论数失败");
            }
        }
    }

    private void decrementCommentCounts(ContentComment comment) {
        commentCounterService.changeCommentCount(comment.getContentId(), -1);
        if (comment.getAnswerId() != null) {
            commentCounterService.changeAnswerCommentCount(comment.getAnswerId(), -1);
        }
    }

    /** 评论数会进入帖子和回答搜索文档，因此两类文档都要登记重建事件。 */
    private void createCommentSearchEvents(ContentComment comment, String triggerType) {
        searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), comment.getContentId(), triggerType);
        if (comment.getAnswerId() != null) {
            searchEventProducer.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), comment.getAnswerId(), triggerType);
        }
    }

    /**
     * C-1/C-4：仅在审核通过后判定 bot 命中，命中才创建触发 Outbox。
     */
    private void maybeCreateBotMentionEvent(ContentComment comment) {
        boolean mentioned = BotMentionDetector.isBotMentioned(
                comment.getContent(),
                quantabotProperties.getBotNickname(),
                comment.getReplyUserId(),
                quantabotProperties.getBotUserId()
        );
        if (!mentioned) {
            return;
        }

        List<String> imageUrls = commentMapper.selectImagesByCommentId(comment.getCommentId());
        String triggerKind = BotMentionDetector.triggerKind(
                comment.getReplyUserId(),
                quantabotProperties.getBotUserId()
        );
        commentEventProducer.createBotMentionEvent(comment, imageUrls, triggerKind);
    }

    /** 在审核事务中创建评论、回答和回复通知 Outbox。 */
    private void createCommentNotificationEvents(ContentComment comment) {
        ContentSnapshotVO content = contentQueryService.getContentSnapshot(comment.getContentId());
        if (content == null) {
            return;
        }

        Long actorUserId = comment.getUserId();
        Long commentId = comment.getCommentId();

        Long answerAuthorId = null;
        if (comment.getAnswerId() != null) {
            AnswerSnapshotVO answer = answerQueryService.getAnswerSnapshot(comment.getAnswerId());
            if (answer != null) {
                answerAuthorId = answer.getUserId();
            }
        }

        boolean isSameAuthor = answerAuthorId != null
                && Objects.equals(content.getPublishUserId(), answerAuthorId);

        if (isSameAuthor) {
            if (!content.getPublishUserId().equals(actorUserId)) {
                NotificationEventMessage notification = NotificationEventMessage.builder()
                        .recipientUserId(content.getPublishUserId())
                        .actorUserId(actorUserId)
                        .type(comment.getAnswerId() != null
                                ? NotificationType.COMMENT_ON_ANSWER.getCode()
                                : NotificationType.COMMENT_ON_CONTENT.getCode())
                        .content(comment.getAnswerId() != null
                                ? "评论了你的回答"
                                : "评论了你的内容")
                        .payload(Map.of(
                                "contentId", content.getContentId(),
                                "answerId", comment.getAnswerId() != null ? comment.getAnswerId() : "",
                                "commentId", commentId
                        ))
                        .build();
                notificationEventProducer.createNotificationEvent(notification, ModerationTargetType.COMMENT.name(), commentId);
            }
        } else {
            if (!content.getPublishUserId().equals(actorUserId)) {
                NotificationEventMessage contentNotification = NotificationEventMessage.builder()
                        .recipientUserId(content.getPublishUserId())
                        .actorUserId(actorUserId)
                        .type(NotificationType.COMMENT_ON_CONTENT.getCode())
                        .content("评论了你的内容")
                        .payload(Map.of(
                                "contentId", content.getContentId(),
                                "commentId", commentId
                        ))
                        .build();
                notificationEventProducer.createNotificationEvent(contentNotification, ModerationTargetType.COMMENT.name(), commentId);
            }

            if (answerAuthorId != null && !answerAuthorId.equals(actorUserId)) {
                NotificationEventMessage answerNotification = NotificationEventMessage.builder()
                        .recipientUserId(answerAuthorId)
                        .actorUserId(actorUserId)
                        .type(NotificationType.COMMENT_ON_ANSWER.getCode())
                        .content("评论了你的回答")
                        .payload(Map.of(
                                "contentId", content.getContentId(),
                                "answerId", comment.getAnswerId(),
                                "commentId", commentId
                        ))
                        .build();
                notificationEventProducer.createNotificationEvent(answerNotification, ModerationTargetType.COMMENT.name(), commentId);
            }
        }

        if (comment.getReplyCommentId() != null && comment.getReplyUserId() != null) {
            if (!comment.getReplyUserId().equals(actorUserId)) {
                NotificationEventMessage replyNotification = NotificationEventMessage.builder()
                        .recipientUserId(comment.getReplyUserId())
                        .actorUserId(actorUserId)
                        .type(NotificationType.COMMENT_REPLY.getCode())
                        .content("回复了你的评论")
                        .payload(Map.of(
                                "contentId", content.getContentId(),
                                "commentId", commentId,
                                "replyCommentId", comment.getReplyCommentId()
                        ))
                        .build();
                notificationEventProducer.createNotificationEvent(replyNotification, ModerationTargetType.COMMENT.name(), commentId);
            }
        }
    }
}
