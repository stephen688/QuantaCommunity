package com.quanta.demo0.service.Impl;

import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.service.ContentAuditService;
import com.quanta.demo0.service.ContentExposureService;
import com.quanta.demo0.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 审核通过后处理服务实现类
 * 职责：
 * 1. 处理内容的审核通过/驳回逻辑
 * 2. 审核通过后触发内容曝光（写入推荐池）
 * 3. 发送审核结果通知给用户
 * 调用方：
 * - AI 审核系统（ContentModerationServiceImpl）
 * - 管理端人工审核（AdminContentServiceImpl）
 * - 定时任务（超时自动审核）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentAuditServiceImpl implements ContentAuditService {


    private final ContentMapper contentMapper;

    private final ContentExposureService contentExposureService;

    private final OutboxEventService outboxEventService;
    // ==================== 审核通过 ====================

    /**
     * 审核通过内容
     * 执行流程：
     * 1. 查询内容是否存在
     * 2. 校验内容是否处于待审状态（防止重复审核）
     * 3. 更新审核状态为"已通过"
     * 4. 触发内容曝光（写入推荐池）
     * 5. 发送审核通过通知给用户
     * @param contentId 内容 ID
     */
    @Override
    @Transactional
    public void approveContent(Long contentId) {
        // 1. 查询帖子
        Content content = contentMapper.selectById(contentId);

        if (content == null) {
            log.warn("自动通过失败，内容不存在 contentId={}", contentId);
            return;
        }

        // 2. 让 MySQL 判断是否仍然是待审核
        int updatedRows = contentMapper.updateAuditStatusIfPending(contentId, AuditStatus.APPROVED.getCode());

        /**
         * 返回 0 说明：
         * 1. 帖子已经被管理员处理；
         * 2. 帖子已经被其他审核实例处理；
         * 3. 帖子已经删除。
         */
        if (updatedRows != 1) {
            log.info("内容已非待审，跳过自动通过 contentId={}", contentId);
            return;
        }

        // 3. 审核状态已经真正发生变化
        content.setAuditStatus(AuditStatus.APPROVED.getCode());


        // 审核状态和 Feed Outbox 在同一个事务中提交。
        outboxEventService.createFeedUpsertEvent(content);

        // 审核状态和 ES 校准 Outbox 在同一个事务中提交。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, "AUDIT_APPROVED");

        // 4. 写入推荐池
        contentExposureService.exposeApprovedContent(content);

        // 5. 在当前事务中创建通知 Outbox
        createAuditNotificationEvent(content, AuditStatus.APPROVED.getCode(), null);
    }

    // ==================== 审核驳回 ====================

    /**
     * 审核驳回内容
     * 执行流程：
     * 1. 查询内容是否存在
     * 2. 校验内容是否处于待审状态（防止重复审核）
     * 3. 更新审核状态为"已驳回"
     * 4. 发送审核驳回通知给用户（包含驳回原因）
     * 注意：
     * - 驳回的内容不会写入推荐池
     * - 用户可以修改后重新提交审核
     * 
     * @param contentId    内容 ID
     * @param rejectReason 驳回原因（可选）
     */
    @Override
    @Transactional
    public void rejectContent(Long contentId, String rejectReason) {
        // 1. 查询帖子
        Content content = contentMapper.selectById(contentId);

        if (content == null) {
            log.warn("自动驳回失败，内容不存在 contentId={}", contentId);
            return;
        }

        // 2. 只有 PENDING 状态才能修改为 REJECTED
        int updatedRows = contentMapper.updateAuditStatusIfPending(contentId, AuditStatus.REJECTED.getCode());

        // 3. 返回 0，说明帖子已经被其他审核操作处理
        if (updatedRows != 1) {
            log.info("内容已非待审，跳过自动驳回 contentId={}", contentId);
            return;
        }

        // 即使 ES 中原本没有文档，也用统一校准事件保证最终状态为删除。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, "AUDIT_REJECTED");

        // 4. 在当前事务中创建通知 Outbox
        createAuditNotificationEvent(content, AuditStatus.REJECTED.getCode(), rejectReason);
    }
    // ==================== 通知发送 ====================

    /**
     * 创建审核结果通知 Outbox。
     *
     * 这里只写 MySQL，
     * 不直接调用 RabbitMQ。
     */
    private void createAuditNotificationEvent(
            Content content,
            Integer auditResult,
            String rejectReason)
    {
        String notifyContent =
                auditResult.equals(
                        AuditStatus.APPROVED.getCode()
                )
                        ? "你的内容已审核通过"
                        : "你的内容审核未通过";

        if (auditResult.equals(AuditStatus.REJECTED.getCode()) && StringUtils.hasText(rejectReason)) {
            notifyContent +=
                    "，原因：" + rejectReason;
        }

        NotificationEventMessage message =
                NotificationEventMessage.builder()
                        .recipientUserId(
                                content.getPublishUserId()
                        )
                        .actorUserId(null)
                        .type(
                                NotificationType
                                        .CONTENT_AUDIT_RESULT
                                        .getCode()
                        )
                        .content(notifyContent)
                        .payload(
                                Map.of(
                                        "contentId",
                                        content.getContentId(),
                                        "auditResult",
                                        auditResult,
                                        "rejectReason",
                                        rejectReason != null
                                                ? rejectReason
                                                : ""
                                )
                        )
                        .build();

        outboxEventService.createNotificationEvent(
                message,
                ModerationTargetType.CONTENT.name(),
                content.getContentId()
        );
    }
}
