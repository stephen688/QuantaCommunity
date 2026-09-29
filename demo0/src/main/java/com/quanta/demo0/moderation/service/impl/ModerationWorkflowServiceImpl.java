package com.quanta.demo0.moderation.service.impl;

import com.quanta.demo0.moderation.enums.ModerationDecision;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.moderation.mq.producer.ModerationProducer;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.result.ModerationWorkflowResult;
import com.quanta.demo0.moderation.service.ContentModerationService;
import com.quanta.demo0.moderation.service.ModerationWorkflowService;
import com.quanta.demo0.answer.service.AnswerAuditService;
import com.quanta.demo0.comment.service.CommentAuditService;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审核可靠消费工作流。
 *
 * <p>工作流返回传输层动作，但不直接操作 RabbitMQ channel。审核目标更新与
 * Inbox SUCCESS 标记在同一个事务方法中完成；监听器只有在工作流返回 ACK 后才确认消息。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationWorkflowServiceImpl
        implements ModerationWorkflowService {

    private static final String CONSUMER_NAME = "moderation-consumer";

    private static final long RETRY_DELAY_SECONDS = 60L;

    private final String instanceId = "moderation-" + UUID.randomUUID();

    private final ContentModerationService moderationService;

    private final ContentAuditService contentAuditService;

    private final AnswerAuditService answerAuditService;

    private final CommentAuditService commentAuditService;

    private final InboxEventService inboxEventService;

    private final ModerationProducer moderationProducer;

    private final AliyunModerationProperties moderationProperties;

    private final PlatformTransactionManager transactionManager;

    @Override
    public ModerationWorkflowResult process(ModerationTaskMessage task) {
        if (task == null || !StringUtils.hasText(task.getEventId())) {
            if (task == null) {
                log.error("审核消息为空，拒绝进入可靠消费链路");
            } else {
                log.error("审核消息缺少 eventId，拒绝进入可靠消费链路，targetType={}, targetId={}",
                        task.getTargetType(), task.getTargetId());
            }
            return ModerationWorkflowResult.DEAD;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(
                    CONSUMER_NAME,
                    instanceId,
                    task
            );

            return switch (acquireResult) {
                case ALREADY_SUCCESS -> {
                    log.info("审核事件已经处理成功，直接 ACK，eventId={}", task.getEventId());
                    yield ModerationWorkflowResult.ACK;
                }
                case BUSY -> {
                    log.info("审核事件正在被其他实例处理，进入重试队列，eventId={}", task.getEventId());
                    yield sendBusyMessageToRetry(task);
                }
                case DEAD -> {
                    log.error("审核事件已经进入 DEAD，转入死信队列，eventId={}", task.getEventId());
                    yield ModerationWorkflowResult.DEAD;
                }
                case ACQUIRED -> processAcquiredMessage(task);
            };
        } catch (Exception exception) {
            log.error("审核消息抢占异常，eventId={}", task.getEventId(), exception);
            return ModerationWorkflowResult.REQUEUE;
        }
    }

    private ModerationWorkflowResult processAcquiredMessage(
            ModerationTaskMessage task
    ) {
        try {
            log.info("开始处理审核任务，eventId={}, targetType={}, targetId={}",
                    task.getEventId(), task.getTargetType(), task.getTargetId());

            ModerationResult result = moderationService.moderate(task);

            if (result.getDecision() == ModerationDecision.ERROR) {
                return handleProcessingFailure(
                        task,
                        result,
                        result.getRejectReason()
                );
            }

            applyResultAndMarkSuccess(task, result);

            log.info("审核任务处理成功，eventId={}, targetId={}",
                    task.getEventId(), task.getTargetId());
            return ModerationWorkflowResult.ACK;
        } catch (Exception exception) {
            log.error("审核任务处理异常，eventId={}, targetId={}",
                    task.getEventId(), task.getTargetId(), exception);

            return handleProcessingFailure(
                    task,
                    null,
                    exception.getClass().getSimpleName() + "：" + exception.getMessage()
            );
        }
    }

    /**
     * 审核目标更新和 Inbox SUCCESS 必须作为一个事务提交。
     */
    private void applyResultAndMarkSuccess(
            ModerationTaskMessage task,
            ModerationResult result
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            dispatchResult(task, result);

            boolean updated = inboxEventService.markSuccess(
                    CONSUMER_NAME,
                    task.getEventId(),
                    instanceId
            );

            if (!updated) {
                throw new IllegalStateException("Inbox 已失去处理权，不能标记 SUCCESS");
            }
        });
    }

    private void dispatchResult(
            ModerationTaskMessage task,
            ModerationResult result
    ) {
        ModerationDecision decision = result.getDecision();
        Long targetId = task.getTargetId();

        switch (task.getTargetType()) {
            case CONTENT -> dispatchContentDecision(targetId, decision, result);
            case ANSWER -> dispatchAnswerDecision(targetId, decision, result);
            case COMMENT -> dispatchCommentDecision(targetId, decision, result);
        }
    }

    private void dispatchContentDecision(
            Long contentId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS -> contentAuditService.approveContent(contentId);
            case REJECT -> contentAuditService.rejectContent(contentId, result.getRejectReason());
            case MANUAL -> log.info("帖子审核疑似，转人工，contentId={}", contentId);
            default -> throw new IllegalStateException("不能处理的审核结果：" + decision);
        }
    }

    private void dispatchAnswerDecision(
            Long answerId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS -> answerAuditService.approveAnswer(answerId);
            case REJECT -> answerAuditService.rejectAnswer(answerId, result.getRejectReason());
            case MANUAL -> log.info("回答审核疑似，转人工，answerId={}", answerId);
            default -> throw new IllegalStateException("不能处理的审核结果：" + decision);
        }
    }

    private void dispatchCommentDecision(
            Long commentId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS -> commentAuditService.approveComment(commentId);
            case REJECT -> commentAuditService.rejectComment(commentId, result.getRejectReason());
            case MANUAL -> log.info("评论审核疑似，转人工，commentId={}", commentId);
            default -> throw new IllegalStateException("不能处理的审核结果：" + decision);
        }
    }

    private ModerationWorkflowResult handleProcessingFailure(
            ModerationTaskMessage task,
            ModerationResult result,
            String lastError
    ) {
        int currentRetryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int maxRetryCount = moderationProperties.getMaxRetryCount();

        try {
            if (currentRetryCount >= maxRetryCount) {
                moderationService.saveFailedRecord(task, result);

                boolean updated = inboxEventService.markDead(
                        CONSUMER_NAME,
                        task.getEventId(),
                        instanceId,
                        lastError
                );

                if (!updated) {
                    throw new IllegalStateException("Inbox 已失去处理权，不能标记 DEAD");
                }

                log.error("审核事件进入 DEAD，eventId={}, retryCount={}",
                        task.getEventId(), currentRetryCount);
                return ModerationWorkflowResult.DEAD;
            }

            int nextRetryCount = currentRetryCount + 1;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS);

            boolean updated = inboxEventService.markRetry(
                    CONSUMER_NAME,
                    task.getEventId(),
                    instanceId,
                    nextRetryCount,
                    nextRetryTime,
                    lastError
            );

            if (!updated) {
                throw new IllegalStateException("Inbox 已失去处理权，不能标记 RETRYING");
            }

            task.setRetryCount(nextRetryCount);

            if (!moderationProducer.sendRetryTask(task)) {
                return ModerationWorkflowResult.REQUEUE;
            }

            log.warn("审核事件等待重试，eventId={}, retryCount={}, nextRetryTime={}",
                    task.getEventId(), nextRetryCount, nextRetryTime);
            return ModerationWorkflowResult.ACK;
        } catch (Exception exception) {
            log.error("审核失败状态处理异常，eventId={}", task.getEventId(), exception);
            return ModerationWorkflowResult.REQUEUE;
        }
    }

    private ModerationWorkflowResult sendBusyMessageToRetry(
            ModerationTaskMessage task
    ) {
        try {
            return moderationProducer.sendRetryTask(task)
                    ? ModerationWorkflowResult.ACK
                    : ModerationWorkflowResult.REQUEUE;
        } catch (Exception exception) {
            log.error("繁忙消息进入重试队列失败，eventId={}", task.getEventId(), exception);
            return ModerationWorkflowResult.REQUEUE;
        }
    }
}
