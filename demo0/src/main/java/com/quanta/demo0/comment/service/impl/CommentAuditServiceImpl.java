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
 *
 * ============================================================
 * 【状态机与三类调用方】
 * ============================================================
 * 状态取值见 AuditStatus：0=PENDING、1=APPROVED、2=REJECTED。四条合法迁移
 * 全部经由 updateAuditStatusIfCurrent 的 CAS UPDATE 完成：
 *   PENDING → APPROVED（approveComment）：机审 PASS / 发布自动通过 / 人工过审；
 *   PENDING → REJECTED（rejectComment）：机审 REJECT / 人工驳回；
 *   REJECTED → APPROVED（approveRejectedComment）：管理端翻案，补齐可见性副作用；
 *   APPROVED → REJECTED（revertApprovedComment）：管理端撤回已曝光内容，回滚计数。
 * 机审结论 MANUAL 不进本类：moderation 工作流只打日志，评论停在 PENDING
 * 等管理端裁决（见 ModerationWorkflowServiceImpl.dispatchCommentDecision）。
 *
 * ============================================================
 * 【为什么每条迁移都走 CAS 而不是"查出来再改状态"？】
 * ============================================================
 * 机审工作流与管理端可能同时操作同一条评论。CAS 写法（WHERE comment_id=?
 * AND audit_status=旧值 AND is_deleted=0，见 CommentMapper.xml 的
 * updateAuditStatusIfCurrent）保证**只有先到的操作生效**，后到者 UPDATE
 * 0 行 → 方法返回 false、安静退出或提示刷新——不需要额外加锁，
 * 也绝不互相覆盖。
 *
 * ============================================================
 * 【可见性副作用的"原子包"】
 * ============================================================
 * 过审瞬间评论从"私有"变"公开"，依赖可见性的派生数据必须在同一事务里
 * 一起生效：内容/回答评论数（GREATEST(0, count±1)，见 ContentMapper
 * .updateCommentCount）、详情缓存失效（evictAfterCommit——事务内注册
 * afterCommit 回调，提交后才真正删 Redis key）、通知/热度/搜索三路
 * Outbox、bot mention 判定。任何一路抛异常整个事务回滚，不会出现
 * "计数加了但评论还在待审"的中间态。
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

    /**
     * 机审回调与发布自动通过的入口（auditUserId=null，机审没有"人"可记）。
     * 【返回值语义】false=评论不存在或已被抢先流转——这不是错误：重复回调
     * 在 MQ 世界是常态，调用方（moderation 工作流）拿到 false 照样标记
     * Inbox SUCCESS 并 ACK，消息不会因此无限重试。
     */
    @Transactional
    @Override
    public boolean approveComment(Long commentId) {
        return approveComment(commentId, null);
    }

    /**
     * 过审主路径：CAS 把 PENDING 抢成 APPROVED，赢了才执行可见性副作用。
     * 【坑】incrementCommentCounts 逐项校验受影响行数、失败即抛异常——让
     * CAS 改掉的状态连同计数、Outbox 一起回滚。计数和状态必须同生共死。
     */
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

    /**
     * 驳回主路径：驳回原因与状态在同一条 CAS SQL 里写入（updateAuditStatusIfCurrent），
     * 不存在"先改状态、再补原因"的中间窗口。被驳评论从未曝光，没有计数/
     * 缓存/索引需要撤——这正是驳回比"过审后撤回"便宜的地方（对照
     * revertApprovedComment）。
     */
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

    /**
     * 管理端翻案：REJECTED → APPROVED。
     * 【与 approveComment 的唯一差别是 CAS 的旧状态值】副作用完全一致——
     * 对系统其余部分而言，"这条评论可见了"与它此前是否被驳过无关，
     * 计数、缓存、通知、热度、搜索、bot 判定一样不能少。
     */
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

    /**
     * 管理端撤回已曝光评论：APPROVED → REJECTED。
     * 【顺序敏感】先 CAS 赢得状态变更、再 decrementCommentCounts——CAS 输了
     * （比如另一个管理员已抢先驳回）就绝不能动计数，否则会把别人没加过的数
     * 再减一遍。
     * 【与 rejectComment 的差异】这条评论曾曝光：计数加过、索引建过、
     * 详情缓存放行过，所以要回滚计数并补 COMMENT_DELETE 的热度/搜索事件。
     */
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

    /** 内容表必加、回答表按需加；任何一处 UPDATE 不到 1 行就抛异常，回滚整个审核事务。 */
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

    /** 与 increment 对称的减量；不校验行数，调用前必须已赢得状态 CAS（见 revertApprovedComment 的顺序约定）。 */
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
     * 判定规则见 BotMentionDetector：直接回复 bot 评论（replyUserId=bot 账号
     * 10000）优先归类 replied，否则按文本 @昵称（quantabot.bot-nickname=框框，
     * 大小写不敏感）归类 mentioned。命中才写 BOT_MENTION_REQUESTED Outbox，
     * 审核事务本身不做任何同步 bot 动作——审核 RT 不受 bot 响应速度影响。
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
    // 【收件人规则】三个潜在收件人各自去重：
    // - 自己操作自己的一律不发（actor == recipient 跳过）；
    // - 题主与答主是同一人时合并为一条（按评论挂的对象选 COMMENT_ON_ANSWER
    //   或 COMMENT_ON_CONTENT 类型），避免同一条评论给同一人连发两条；
    // - 楼内回复（replyCommentId 非空）另发一条 COMMENT_REPLY 给被回复人。
    // 事件以 COMMENT+评论 ID 作为聚合挂载点（aggregateType/aggregateId）写入
    // Outbox，与审核状态同事务提交。
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
