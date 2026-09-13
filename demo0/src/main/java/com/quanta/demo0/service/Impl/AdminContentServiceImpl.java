package com.quanta.demo0.service.Impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.dto.ContentAdminQueryDTO;
import com.quanta.demo0.dto.ContentAuditDTO;
import com.quanta.demo0.dto.ContentReportHandleDTO;
import com.quanta.demo0.dto.ContentReportQueryDTO;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentReport;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.rag.vector.ContentVectorSyncService;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.service.AdminAuditRecorder;
import com.quanta.demo0.service.AdminContentService;
import com.quanta.demo0.service.ContentExposureService;
import com.quanta.demo0.service.OutboxEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.quanta.demo0.constant.RedisConstants.*;

/**
 * 管理端内容服务实现类。
 *
 * 核心职责：
 * 1. 提供内容分页查询、人工审核、删除、举报处理等后台治理能力；
 * 2. 与内容曝光服务协同，控制帖子在推荐池、Feed、搜索索引中的可见性；
 * 3. 维护审核通知与举报处理状态，确保治理链路可追踪。
 *
 * 设计说明：
 * - 关键状态流转置于事务内，避免审核状态与外部副作用错位；
 * - 通过 ContentExposureService 复用"通过曝光/驳回下线"统一逻辑。
 */
@Service
@Slf4j
public class AdminContentServiceImpl  implements AdminContentService {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private ContentVectorSyncService contentVectorSyncService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private OutboxEventService outboxEventService;
    @Autowired
    private ContentExposureService contentExposureService;

    @Autowired
    private AdminAuditRecorder adminAuditRecorder;


        /**
         * 分页查询内容列表
         * 执行流程：
         * 1. PageHelper.startPage() 开启分页
         * 2. 调用 Mapper 执行 SQL 查询
         * 3. 封装为 PageResult 返回
         * @param query 查询条件
         * @return 分页结果
         */
        @Override
        public PageResult pageQuery(ContentAdminQueryDTO query) {
            PageHelper.startPage(query.getPageNum(), query.getPageSize());
            Page<Content> page = contentMapper.pageAdmin(query);
            return new PageResult(page.getTotal(), page.getResult());
        }

        /**
         * 审核内容
         * 执行流程：
         * 1. 校验参数合法性
         * 2. 查询内容是否存在
         * 3. 记录原审核状态
         * 4. 更新审核状态
         * 5. 根据状态变化同步 ES 和向量库
         *    - 待审核 → 通过：同步到 ES 和向量库
         *    - 已通过 → 驳回：从 ES 和向量库删除
         *    - 已驳回 → 通过：同步到 ES 和向量库
         *
         * @param auditDTO 审核信息
         */
    @Override
    @Transactional
    public void audit(ContentAuditDTO auditDTO) {
        // 1. 校验参数
        if (auditDTO.getContentId() == null) {
            throw new ContentFailedException("内容 ID 不能为空");
        }
        if (auditDTO.getAuditResult() == null ||
                (auditDTO.getAuditResult() != 1 && auditDTO.getAuditResult() != 2)) {
            throw new ContentFailedException("审核结果不合法（1-通过 2-驳回）");
        }

        // 2. 查询内容是否存在
        Content content = contentMapper.selectById(auditDTO.getContentId());
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }

        // 3. 记录原审核状态
        Integer oldAuditStatus = content.getAuditStatus();

        // 4. 更新审核状态
        Content updateContent = new Content();
        updateContent.setContentId(auditDTO.getContentId());
        updateContent.setAuditStatus(auditDTO.getAuditResult());
        updateContent.setUpdateTime(LocalDateTime.now());
        contentMapper.update(updateContent);

        if (!oldAuditStatus.equals(auditDTO.getAuditResult())) {
            String triggerType = auditDTO.getAuditResult() == 1 ? "AUDIT_APPROVED" : "AUDIT_REJECTED";

            // 帖子审核状态和 ES 校准事件在同一个事务中提交。
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), auditDTO.getContentId(), triggerType);

            // 专业区帖子状态变化时，其已通过回答也需要根据父帖当前状态重新校准。
            if (content.getContentType() != null && content.getContentType() == 2) {
                List<QuestionAnswer> answers = questionMapper.selectAnswersByQuestionId(auditDTO.getContentId());
                for (QuestionAnswer answer : answers) {
                    outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answer.getAnswerId(), "PARENT_CONTENT_" + triggerType);
                }
            }
        }

        // 5. 根据状态变化同步曝光侧效应
        if (oldAuditStatus == 0 && auditDTO.getAuditResult() == 1) {
            content.setAuditStatus(auditDTO.getAuditResult());
            // 人工审核状态和 Feed UPSERT Outbox 在同一个事务中提交。
            outboxEventService.createFeedUpsertEvent(content);
            contentExposureService.exposeApprovedContent(content);
            log.info("审核通过（待审→通过），已执行曝光，contentId={}", auditDTO.getContentId());
        } else if (oldAuditStatus == 1 && auditDTO.getAuditResult() == 2) {
            // 已曝光帖子改为驳回时，可靠登记 Feed DELETE 事件。
            outboxEventService.createFeedDeleteEvent(content);
            contentExposureService.hideRejectedContent(auditDTO.getContentId());
            log.info("审核驳回（通过→驳回），已清理曝光，contentId={}", auditDTO.getContentId());
        } else if (oldAuditStatus == 2 && auditDTO.getAuditResult() == 1) {
            content.setAuditStatus(auditDTO.getAuditResult());
            outboxEventService.createFeedUpsertEvent(content);
            contentExposureService.exposeApprovedContent(content);
            log.info("审核通过（驳回→通过），已执行曝光，contentId={}", auditDTO.getContentId());
        }

        // ========== 新增：审核结果通知 ==========
// 只有审核状态发生变化时才发送通知
        if (!oldAuditStatus.equals(auditDTO.getAuditResult())) {
            String notifyContent = auditDTO.getAuditResult() == 1 ? "你的内容已审核通过" : "你的内容审核未通过";
            if (auditDTO.getAuditResult() == 2 && auditDTO.getRejectReason() != null) {
                notifyContent += "，原因：" + auditDTO.getRejectReason();
            }

            NotificationEventMessage auditNotification = NotificationEventMessage.builder()
                    .recipientUserId(content.getPublishUserId())
                    .actorUserId(null) // 系统通知，无具体触发者
                    .type(NotificationType.CONTENT_AUDIT_RESULT.getCode())
                    .content(notifyContent)
                    .payload(Map.of(
                            "contentId", auditDTO.getContentId(),
                            "auditResult", auditDTO.getAuditResult(),
                            "rejectReason", auditDTO.getRejectReason() != null ? auditDTO.getRejectReason() : ""
                    ))
                    .build();
            // 帖子审核状态和审核结果通知 Outbox 在同一个事务中提交。
            outboxEventService.createNotificationEvent(auditNotification, ModerationTargetType.CONTENT.name(), auditDTO.getContentId());
        }
    }

    //TODO: 后续可以抽取一个公共方法，专门处理内容删除的业务逻辑，deleteContent 和 deleteContentByAdmin 都调用这个公共方法，避免代码重复
    /**
     * 管理端删除内容
     * 执行流程：
     * 与 deleteContent 完全一致，唯一区别是跳过发布者权限校验
     *
     * @param contentId 内容 ID
     */
    @Override
    @Transactional
    public void deleteContent(Long contentId) {
        // 1. 参数校验
        if (contentId == null) {
            throw new ContentFailedException("contentId不能为空");
        }
        // 2. 查询内容是否存在
        Content content = contentMapper.selectById(contentId);
        if (content == null) {
            throw new ContentFailedException("内容不存在");
        }
        // 3. 跳过权限校验（管理端无需判断发布者）

        // 4. 物理删除内容图片
        contentMapper.deleteContentImages(contentId);
        // 5. 物理删除内容点赞记录
        contentMapper.deleteContentLikedByContentId(contentId);
        // 补：删除收藏记录
        contentMapper.deleteContentCollectByContentId(contentId);
        // 6. 物理删除评论下面的图片
        contentMapper.deleteContentCommentImages(contentId);
        // 7. 物理删除评论下面的点赞记录
        contentMapper.deleteContentCommentLiked(contentId);
        // 8. 软删除评论
        contentMapper.softDeleteContentComment(contentId);
        // 删除前先记住已经进入 ES 的回答，软删除后由校准事件清理回答索引。
        List<QuestionAnswer> answers = content.getContentType() != null && content.getContentType() == 2
                ? questionMapper.selectAnswersByQuestionId(contentId)
                : List.of();

        // 9. 如果是专业区，删除专业区内容
        if (content.getContentType() != null && content.getContentType() == 2) {
            questionMapper.softDeleteAnswers(contentId);
        }
        // 10. 软删除内容本身
        contentMapper.softDeleteContent(contentId);

        // 删除状态和 Feed DELETE Outbox 在同一个事务中提交。
        outboxEventService.createFeedDeleteEvent(content);

        // 帖子和关联回答删除状态与 ES 校准事件一起提交。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), contentId, "DELETE");
        for (QuestionAnswer answer : answers) {
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), answer.getAnswerId(), "PARENT_CONTENT_DELETE");
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 11. 删最新推荐流
                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_ALL_KEY, contentId.toString());
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }
                    // 11.2 删热度推荐流
                    stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_ALL_KEY, contentId.toString());
                    if (content.getContentType() != null) {
                        if (content.getContentType() == 1) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_LIFE_KEY, contentId.toString());
                        } else if (content.getContentType() == 2) {
                            stringRedisTemplate.opsForZSet().remove(RECOMMEND_HOT_PROFESSIONAL_KEY, contentId.toString());
                        }
                    }

                    // 12. 删除redis中的点赞记录
                    String likeKey = CONTENT_LIKED_KEY + contentId;
                    stringRedisTemplate.delete(likeKey);

                    // 13. 删除redis中的收藏记录
                    String collectKey = CONTENT_COLLECT_KEY + contentId;
                    stringRedisTemplate.delete(collectKey);

                    // 16. 删除向量库
                    contentVectorSyncService.deleteByContentId(contentId);

                    log.info("管理端删除内容成功，contentId={}", contentId);
                }
            });
        }
    }

    /**
     * 分页查询帖子举报列表
     * 执行流程：
     * 1. PageHelper.startPage() 开启分页
     * 2. 调用 Mapper 执行 SQL 查询
     * 3. 封装为 PageResult 返回
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @Override
    public PageResult pageReport(ContentReportQueryDTO query) {
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<ContentReport> page = contentMapper.pageReport(query);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 处理帖子举报
     * 执行流程：
     * 1. 校验参数
     * 2. 查询举报记录是否存在
     * 3. 更新举报处理信息（状态、处理人、处理结果、备注、时间）
     * 4. 如果处理结果包含删除帖子，调用删除方法
     * @param handleDTO 处理信息
     */
    @Override
    @Transactional
    public void handleReport(ContentReportHandleDTO handleDTO) {
        // 1. 校验参数
        if (handleDTO.getReportId() == null) {
            throw new ContentFailedException("举报记录 ID 不能为空");
        }
        if (handleDTO.getHandleResult() == null ||
                handleDTO.getHandleResult() < 1 || handleDTO.getHandleResult() > 4) {
            throw new ContentFailedException("处理结果不合法（1-删除帖子 2-警告用户 3-删除+警告 4-驳回举报）");
        }

        // 2. 查询举报记录是否存在
        ContentReport report = contentMapper.getReportById(handleDTO.getReportId());
        if (report == null) {
            throw new ContentFailedException("举报记录不存在");
        }

        // 3. 更新举报处理信息
        ContentReport updateReport = new ContentReport();
        updateReport.setId(handleDTO.getReportId());
        updateReport.setStatus(2); // 已处理
        updateReport.setHandlerId(BaseContext.getCurrentId()); // 当前管理员 ID
        updateReport.setHandleResult(handleDTO.getHandleResult());
        updateReport.setHandleRemark(handleDTO.getHandleRemark());
        updateReport.setHandleTime(LocalDateTime.now());
        updateReport.setUpdateTime(LocalDateTime.now());
        contentMapper.updateReport(updateReport);

        // 4. 如果处理结果包含删除帖子（1 或 3），执行删除
        if (handleDTO.getHandleResult() == 1 || handleDTO.getHandleResult() == 3) {
            deleteContent(report.getContentId());
            log.info("处理举报：已删除帖子，reportId={}, contentId={}", handleDTO.getReportId(), report.getContentId());
        }

        log.info("处理举报成功，reportId={}, handleResult={}", handleDTO.getReportId(), handleDTO.getHandleResult());


        // ========== 新增：举报处理结果通知 ==========
        String handleResultText = switch (handleDTO.getHandleResult()) {
            case 1 -> "你举报的帖子已被删除";
            case 2 -> "你举报的帖子已处理，已警告用户";
            case 3 -> "你举报的帖子已处理，已删除并警告用户";
            case 4 -> "你举报的帖子经核实无需处理";
            default -> "你举报的帖子已处理";
        };
        if (handleDTO.getHandleRemark() != null && !handleDTO.getHandleRemark().isEmpty()) {
            handleResultText += "，备注：" + handleDTO.getHandleRemark();
        }

        NotificationEventMessage reportNotification = NotificationEventMessage.builder()
                .recipientUserId(report.getReporterId())
                .actorUserId(null) // 系统通知
                .type(NotificationType.CONTENT_REPORT_RESULT.getCode())
                .content(handleResultText)
                .payload(Map.of(
                        "contentId", report.getContentId(),
                        "reportId", handleDTO.getReportId(),
                        "handleResult", handleDTO.getHandleResult()
                ))
                .build();
        // 举报处理状态和结果通知 Outbox 在同一个事务中提交。
        outboxEventService.createNotificationEvent(reportNotification, ModerationTargetType.CONTENT.name(), report.getContentId());

        // 审计：举报处理成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.REPORT_HANDLE,
                "REPORT",
                String.valueOf(handleDTO.getReportId()),
                "reportStatus=PENDING",
                "reportStatus=HANDLED"
        );
    }
}

