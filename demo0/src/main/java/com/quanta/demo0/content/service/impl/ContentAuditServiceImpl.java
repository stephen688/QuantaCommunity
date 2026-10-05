package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.search.service.TrendingCacheInvalidator;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.content.mapper.ContentMapper;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.feed.service.ContentExposureService;
import com.quanta.demo0.content.mq.producer.ContentEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
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
 *
 * ============================================================
 * 【这个类是"状态迁移的汇聚点"—— 三条来源、一个出口】
 * ============================================================
 * 机审消费者（ModerationWorkflowServiceImpl / ModerationResultServiceImpl）和
 * 发布侧机审关闭时的自动通过（见 ContentCommandServiceImpl.schedulePostPublishActions）
 * 都收敛到 approveContent 这一个方法。
 * 好处：状态迁移的副作用（标签/Feed/搜索/推荐池/通知）只实现一次，
 * 任何来源都不会漏掉某个下游 —— **入口可以多，状态机必须只有一个**。
 * （注意：管理端人工审核走 AdminContentServiceImpl.audit 自己的一套 ——
 * 它要支持"已通过→驳回、驳回→通过"这类机审 CAS 不允许的回流，见那边的说明。）
 *
 * 【面试追问：机审（MQ 消费者）和管理端审核并发审同一帖会怎样？】
 * 见 approveContent 里的 updateAuditStatusIfPending —— 带状态条件的 UPDATE：
 *   UPDATE tb_content SET audit_status=1 WHERE content_id=? AND audit_status=0
 * 两条并发路径只有一条能影响 1 行，另一条拿到 0 行自动放弃。
 * **这就是"乐观并发 + 状态机前置条件"，等价于一次 CAS** ——
 * 不用分布式锁、不用 SELECT FOR UPDATE，数据库行锁 + WHERE 条件就够了。
 * （与互动域"insert 明细受影响行数判幂等"是同一思想在不同场景的应用，
 * 见 AnswerInteractionServiceImpl 里 insertAnswerLiked 对 inserted == 1 的判断。）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentAuditServiceImpl implements ContentAuditService {


    private final ContentMapper contentMapper;

    private final ContentExposureService contentExposureService;

    private final ContentEventProducer contentEventProducer;
    private final SearchEventProducer searchEventProducer;
    private final NotificationEventProducer notificationEventProducer;

    private final TrendingCacheInvalidator trendingCacheInvalidator;

    private final ContentDetailCacheInvalidator contentDetailCacheInvalidator;
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
     *
     * ============================================================
     * 【方法的骨架 = 一行 CAS + 一组同事务 Outbox】
     * ============================================================
     *   updateAuditStatusIfPending（CAS，见类注释）→ 拿到 1 行才继续
     *     → 3 个 Outbox 事件（标签/Feed/搜索）与审核状态**同一个事务**提交
     *     → exposeApprovedContent 写推荐池
     *     → 通知 Outbox
     *
     * 【为什么 CAS 失败（0 行）是 return 而不是抛异常？】
     * 0 行 = 别的路径已经审过了 = **幂等命中，不是错误**。
     * 机审消费者超时重试、定时任务补审，都会反复进这个方法 ——
     * 如果抛异常，MQ 会当成消费失败无限重试；静默 return 才是幂等消费者的正确姿势。
     *
     * 【面试高频：exposeApprovedContent 是写 Redis，为什么不在 afterCommit？】
     * 注意它在事务方法里同步执行 —— 如果写完 Redis 事务回滚了怎么办？
     * 答案在推荐池的设计里：ZSET 是**覆盖式写入**（热度分重算时全量重写），
     * 不存在"回滚后残留一条多余成员"的累积效应；且 ZSET 成员多了个
     * 未过审帖子的后果有限（详情接口还有 NOT_APPROVED 负缓存拦着）。
     * —— 不是所有 Redis 写都值得 afterCommit，**看回滚后的残留是否可自愈**。
     *
     * 【主题标签事件为什么也在这里发？】
     * 标签是 LLM 异步打的，但事件必须在"内容可见"的同事务登记 ——
     * 否则机审链路和标签链路对"内容何时算就绪"会产生两个真相。
     * 原则：**一个业务状态迁移的所有下游事件，在同一事务里登记**。
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

        // 数据库状态已经真实变化；后续异常导致事务回滚时，提交回调不会执行。
        trendingCacheInvalidator.evictAfterCommit("content-audit-approved");
        contentDetailCacheInvalidator.evictAfterCommit(contentId, "content-audit-approved");

        // 3. 审核状态已经真正发生变化
        content.setAuditStatus(AuditStatus.APPROVED.getCode());

        // 审核通过与主题标签 Outbox 同事务；模型处理在独立消费者中异步执行，不阻塞审核。
        contentEventProducer.createContentTopicTagEvent(contentId);

        // 审核状态和 Feed Outbox 在同一个事务中提交。
        contentEventProducer.createFeedUpsertEvent(content);

        // 审核状态和 ES 校准 Outbox 在同一个事务中提交。
        searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, "AUDIT_APPROVED");

        // 4. 写入推荐池
        contentExposureService.exposeApprovedContent(toSnapshot(content));

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
     *
     * 【与 approveContent 的不对称是故意的】
     * 驳回没有 Feed/推荐池/标签事件 —— 因为驳回的帖子从来就没进过这些池子
     * （PENDING 状态时不会 expose）。只需要：搜索对账事件（保证 ES 里没有残留）
     * + 用户通知。**下游事件跟着"可见性变化"走：可见 = 进池，不可见 = 什么都不用撤**。
 *
 * （边界：CAS 限定只有 PENDING 能被驳回，所以本方法面对的帖子必然从未进过推荐池；
 * 而"已通过→驳回"的下架清理是另一种状态迁移，
 * 由 AdminContentServiceImpl.audit 的 hideRejectedContent 分支负责 —— 两条路径别混。）
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
        searchEventProducer.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, "AUDIT_REJECTED");

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

        notificationEventProducer.createNotificationEvent(
                message,
                ModerationTargetType.CONTENT.name(),
                content.getContentId()
        );
    }

    private ContentSnapshotVO toSnapshot(Content content) {
        return ContentSnapshotVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .tags(content.getTags())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .isDeleted(content.getIsDeleted())
                .createTime(content.getCreateTime())
                .updateTime(content.getUpdateTime())
                .likedCount(content.getLiked())
                .commentCount(content.getCommentCount())
                .collectCount(content.getCollectCount())
                .build();
    }
}
