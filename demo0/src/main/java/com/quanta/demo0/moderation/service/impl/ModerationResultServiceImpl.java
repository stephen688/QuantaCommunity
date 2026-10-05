package com.quanta.demo0.moderation.service.impl;

import com.quanta.demo0.moderation.enums.ModerationDecision;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.answer.service.AnswerAuditService;
import com.quanta.demo0.comment.service.CommentAuditService;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.quanta.demo0.moderation.service.ModerationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核结果回调实现：分发机审结论 + Inbox 标记 SUCCESS，两步强制同事务。
 *
 * ============================================================
 * 【与 ModerationWorkflowServiceImpl 的关系——同职责的对照实现】
 * ============================================================
 * "dispatchResult + markSuccess 必须原子"这一段，消费主链路实际走的是
 * ModerationWorkflowServiceImpl（同事务段是私有方法，@Transactional 声明式
 * 事务不生效，故用 TransactionTemplate 编程式事务）；本类把同样的事
 * 抽成 public 入口，就能直接用方法级 @Transactional（默认 REQUIRED，
 * 被外部带事务调用时加入调用方事务）。
 * 当前 ModerationConsumer 只注入 ModerationWorkflowService，不注入本接口
 * （ConsumerReliabilityTests 对此有断言）。**读代码以 ModerationWorkflowServiceImpl
 * 为主线，本类当同职责的对照实现读**——两边的分发逻辑刻意保持同构。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationResultServiceImpl
        implements ModerationResultService {

    private final ContentAuditService contentAuditService;

    private final AnswerAuditService answerAuditService;

    private final CommentAuditService commentAuditService;

    private final InboxEventService inboxEventService;

    /**
     * 机审结论的统一出口（事务语义见类注释）。
     * 【markSuccess 返回 false 为什么抛异常而不是记日志了事？】
     * markSuccessByOwner 的 SQL 带 WHERE status='PROCESSING' AND locked_by=本实例
     * （见 InboxEventMapper.xml），false = 60 秒租约已被其他实例抢走——
     * 若吞掉这个返回值，会出现两个实例都推进业务状态的双写。
     * 抛 IllegalStateException 让 @Transactional 整体回滚，
     * dispatchResult 已做的状态变更一并撤销。
     */
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

    /** 按目标类型路由到对应域；结构与 ModerationWorkflowServiceImpl.dispatchResult 同构。 */
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

    /**
     * 机审结论 → 帖子状态机。
     * 【MANUAL 只打日志】帖子留在待审状态等人工裁决（走管理端 AdminContentService），
     * 机审证据经 AdminModerationService 查询；ERROR 决策到不了这里，default 兜底抛异常。
     */
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

    /** 回答版决策分发，语义同 dispatchContentDecision（MANUAL 同样只留痕不改状态）。 */
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

    /** 评论版决策分发，语义同 dispatchContentDecision。 */
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
