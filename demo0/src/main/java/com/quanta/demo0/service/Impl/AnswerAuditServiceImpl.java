package com.quanta.demo0.service.Impl;

import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.rag.vector.AnswerVectorSyncService;
import com.quanta.demo0.service.AnswerAuditService;
import com.quanta.demo0.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 回答审核服务实现类。
 *
 * 核心职责：
 * 1. 统一处理回答审核通过/驳回状态流转；
 * 2. 审核通过后同步 ES 与向量索引，保证检索结果与可见状态一致；
 * 3. 向作者发送审核结果通知，与人工审核链路保持一致体验。
 *
 * 设计说明：
 * - 仅处理待审状态，避免 AI 与人工并发审核导致状态覆盖；
 * - 非关键副作用失败时记录日志，确保审核主流程可继续完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerAuditServiceImpl implements AnswerAuditService {

    private final QuestionMapper questionMapper;
    private final AnswerVectorSyncService answerVectorSyncService;
    private final OutboxEventService outboxEventService;
    @Override
    @Transactional
    public void approveAnswer(Long answerId) {
        QuestionAnswer answer = questionMapper.selectById(answerId);

        if (answer == null) {
            log.warn("回答自动通过失败，回答不存在 answerId={}", answerId);
            return;
        }

        // 条件更新保证只有一个审核操作可以从 PENDING 改成 APPROVED。
        int updatedRows = questionMapper.updateAnswerAuditStatusIfPending(answerId, AuditStatus.APPROVED.getCode(), null);

        if (updatedRows != 1) {
            log.info("回答已非待审，跳过自动通过 answerId={}", answerId);
            return;
        }

        // 回答状态和通知 Outbox 加入同一个数据库事务。
        createAuditNotificationEvent(answer, AuditStatus.APPROVED.getCode(), null);

        // 回答审核状态和 ES 校准 Outbox 一起提交。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "AUDIT_APPROVED");

        // 向量库不在本次 Outbox 计划内，仍然在事务提交后同步。
        scheduleAnswerIndexSync(answerId);
    }

    @Override
    @Transactional
    public void rejectAnswer(Long answerId, String rejectReason) {
        QuestionAnswer answer = questionMapper.selectById(answerId);

        if (answer == null) {
            log.warn("回答自动驳回失败，回答不存在 answerId={}", answerId);
            return;
        }

        // rejectReason 和审核状态由同一条 SQL 一起更新。
        int updatedRows = questionMapper.updateAnswerAuditStatusIfPending(answerId, AuditStatus.REJECTED.getCode(), rejectReason);

        if (updatedRows != 1) {
            log.info("回答已非待审，跳过自动驳回 answerId={}", answerId);
            return;
        }

        // 不直接发送 RabbitMQ，只在当前事务中写通知 Outbox。
        createAuditNotificationEvent(answer, AuditStatus.REJECTED.getCode(), rejectReason);

        // 即使 ES 原本没有该回答，也通过校准事件保证最终状态为删除。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answerId, "AUDIT_REJECTED");
    }


    private void scheduleAnswerIndexSync(Long answerId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            syncAnswerIndex(answerId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 只有回答审核事务真正提交后，才允许写入向量库。
                syncAnswerIndex(answerId);
            }
        });
    }




    /** 审核通过后写入向量库 */
    private void syncAnswerIndex(Long answerId) {
        try {
            answerVectorSyncService.upsertByAnswerId(answerId);
            log.info("[RAG] 回答审核通过后同步向量成功: answerId={}", answerId);
        } catch (Exception e) {
            log.error("[RAG] 回答审核通过后同步向量失败: answerId={}", answerId, e);
        }
    }

    private void createAuditNotificationEvent(QuestionAnswer answer, Integer auditResult, String rejectReason) {
        String notifyContent = auditResult.equals(AuditStatus.APPROVED.getCode()) ? "你的回答已审核通过" : "你的回答审核未通过";

        if (auditResult.equals(AuditStatus.REJECTED.getCode()) && StringUtils.hasText(rejectReason)) {
            notifyContent += "，原因：" + rejectReason;
        }

        NotificationEventMessage message = NotificationEventMessage.builder()
                .recipientUserId(answer.getUserId())
                .actorUserId(null)
                .type(NotificationType.ANSWER_AUDIT_RESULT.getCode())
                .content(notifyContent)
                .payload(Map.of(
                        "answerId", answer.getAnswerId(),
                        "auditResult", auditResult,
                        "rejectReason", rejectReason != null ? rejectReason : ""
                ))
                .build();

        // 通知 Outbox 会和回答审核状态、审核 Inbox SUCCESS 一起提交。
        outboxEventService.createNotificationEvent(message, ModerationTargetType.ANSWER.name(), answer.getAnswerId());
    }
}
