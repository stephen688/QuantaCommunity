package com.quanta.demo0.service.Impl;

import com.quanta.demo0.annotation.ModerationDecision;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.mq.message.ModerationTaskMessage;
import com.quanta.demo0.service.AnswerAuditService;
import com.quanta.demo0.service.CommentAuditService;
import com.quanta.demo0.service.ContentAuditService;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.ModerationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationResultServiceImpl
        implements ModerationResultService {

    private final ContentAuditService contentAuditService;

    private final AnswerAuditService answerAuditService;

    private final CommentAuditService commentAuditService;

    private final InboxEventService inboxEventService;

    @Override
    @Transactional
    public void handleResultAndMarkSuccess(
            ModerationTaskMessage task,
            ModerationResult result,
            String consumerName,
            String instanceId
    ) {
        /**
         * 先修改帖子审核状态。
         */
        dispatchResult(task, result);

        /**
         * 再把 Inbox 改成 SUCCESS。
         * 这两个数据库操作加入同一个事务。
         * 任意一个失败，事务一起回滚。
         */
        boolean updated =
                inboxEventService.markSuccess(
                        consumerName,
                        task.getEventId(),
                        instanceId
                );

        if (!updated) {
            throw new IllegalStateException(
                    "Inbox 已失去处理权，不能标记 SUCCESS"
            );
        }
    }

    private void dispatchResult(
            ModerationTaskMessage task,
            ModerationResult result
    ) {
        ModerationDecision decision =
                result.getDecision();

        ModerationTargetType targetType =
                task.getTargetType();

        Long targetId = task.getTargetId();

        switch (targetType) {
            case CONTENT ->
                    dispatchContentDecision(
                            targetId,
                            decision,
                            result
                    );

            case ANSWER ->
                    dispatchAnswerDecision(
                            targetId,
                            decision,
                            result
                    );

            case COMMENT ->
                    dispatchCommentDecision(
                            targetId,
                            decision,
                            result
                    );
        }
    }

    private void dispatchContentDecision(
            Long contentId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS -> {
                log.info(
                        "帖子审核通过，contentId={}",
                        contentId
                );
                contentAuditService
                        .approveContent(contentId);
            }

            case REJECT -> {
                log.info(
                        "帖子审核驳回，contentId={}, reason={}",
                        contentId,
                        result.getRejectReason()
                );
                contentAuditService.rejectContent(
                        contentId,
                        result.getRejectReason()
                );
            }

            case MANUAL ->
                    log.info(
                            "帖子审核疑似，转人工，contentId={}",
                            contentId
                    );

            default ->
                    throw new IllegalStateException(
                            "不能处理的审核结果：" + decision
                    );
        }
    }

    private void dispatchAnswerDecision(
            Long answerId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS ->
                    answerAuditService
                            .approveAnswer(answerId);

            case REJECT ->
                    answerAuditService.rejectAnswer(
                            answerId,
                            result.getRejectReason()
                    );

            case MANUAL ->
                    log.info(
                            "回答审核疑似，转人工，answerId={}",
                            answerId
                    );

            default ->
                    throw new IllegalStateException(
                            "不能处理的审核结果：" + decision
                    );
        }
    }

    private void dispatchCommentDecision(
            Long commentId,
            ModerationDecision decision,
            ModerationResult result
    ) {
        switch (decision) {
            case PASS ->
                    commentAuditService
                            .approveComment(commentId);

            case REJECT ->
                    commentAuditService.rejectComment(
                            commentId,
                            result.getRejectReason()
                    );

            case MANUAL ->
                    log.info(
                            "评论审核疑似，转人工，commentId={}",
                            commentId
                    );

            default ->
                    throw new IllegalStateException(
                            "不能处理的审核结果：" + decision
                    );
        }
    }
}
