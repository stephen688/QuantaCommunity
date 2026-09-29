package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.content.service.AdminContentService;
import com.quanta.demo0.interaction.dto.ContentReportHandleDTO;
import com.quanta.demo0.interaction.dto.ContentReportQueryDTO;
import com.quanta.demo0.interaction.mapper.ContentReportMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;
import com.quanta.demo0.interaction.dto.ContentReportDTO;
import com.quanta.demo0.interaction.entity.ContentReport;
import com.quanta.demo0.interaction.entity.CommentReport;
import com.quanta.demo0.interaction.dto.CommentReportHandleDTO;
import com.quanta.demo0.interaction.dto.CommentReportQueryDTO;
import com.quanta.demo0.interaction.mapper.CommentReportMapper;
import com.quanta.demo0.comment.service.AdminCommentService;
import com.quanta.demo0.interaction.mapper.ContentInteractionMapper;
import com.quanta.demo0.interaction.service.ReportGovernanceService;
import com.quanta.demo0.platform.security.context.BaseContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 互动域举报治理：举报事实、处理状态和通知 Outbox 同事务。
 * 对象删除委托内容/评论域公开管理 Service，不直接访问对方 Mapper。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportGovernanceServiceImpl implements ReportGovernanceService {

    private final ContentCounterService contentCounterService;
    private final ContentInteractionMapper contentInteractionMapper;
    private final ContentReportMapper contentReportMapper;
    private final CommentReportMapper commentReportMapper;
    private final AdminCommentService adminCommentService;
    private final AdminContentService adminContentService;
    private final NotificationEventProducer notificationEventProducer;
    private final AdminAuditRecorder adminAuditRecorder;

    @Override
    @Transactional
    public void reportContent(ContentReportDTO contentReportDTO) {
        if (contentReportDTO.getContentId() == null) {
            throw new ContentFailedException("帖子 ID 不能为空");
        }
        if (contentReportDTO.getReportType() == null
                || contentReportDTO.getReportType() < 1
                || contentReportDTO.getReportType() > 5) {
            throw new ContentFailedException("举报类型不合法（1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他）");
        }
        if (contentCounterService.getContentSnapshot(contentReportDTO.getContentId()) == null) {
            throw new ContentFailedException("帖子不存在");
        }

        Long reporterId = BaseContext.getCurrentId();
        ContentReport existing = contentInteractionMapper.selectValidReportByContentAndUser(
                contentReportDTO.getContentId(), reporterId);
        if (existing != null) {
            throw new ContentFailedException("您已举报过该帖子，请勿重复举报");
        }

        LocalDateTime now = LocalDateTime.now();
        ContentReport report = ContentReport.builder()
                .contentId(contentReportDTO.getContentId())
                .reportType(contentReportDTO.getReportType())
                .reporterId(reporterId)
                .status(0)
                .isDeleted(0)
                .createTime(now)
                .updateTime(now)
                .build();
        contentInteractionMapper.insertContentReport(report);
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
        Page<ContentReport> page = contentReportMapper.pageReport(query);
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
        ContentReport report = contentReportMapper.getReportById(handleDTO.getReportId());
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
        contentReportMapper.updateReport(updateReport);

        // 4. 如果处理结果包含删除帖子（1 或 3），执行删除
        if (handleDTO.getHandleResult() == 1 || handleDTO.getHandleResult() == 3) {
            adminContentService.deleteContent(report.getContentId());
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
        notificationEventProducer.createNotificationEvent(reportNotification, ModerationTargetType.CONTENT.name(), report.getContentId());

        // 审计：举报处理成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.REPORT_HANDLE,
                "REPORT",
                String.valueOf(handleDTO.getReportId()),
                "reportStatus=PENDING",
                "reportStatus=HANDLED"
        );
    }

    /**
     * 分页查询评论举报列表
     * 执行流程：
     * 1. PageHelper.startPage() 开启分页
     * 2. 调用 Mapper 执行 SQL 查询
     * 3. 封装为 PageResult 返回
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @Override
    public PageResult pageReport(CommentReportQueryDTO query) {
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<CommentReport> page = commentReportMapper.pageReport(query);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 处理评论举报
     *
     * 执行流程：
     * 1. 校验参数
     * 2. 查询举报记录是否存在
     * 3. 更新举报处理信息（状态、处理人、处理结果、备注、时间）
     * 4. 如果处理结果包含删除评论，调用删除方法
     *
     * @param handleDTO 处理信息
     */
    @Override
    @Transactional
    public void handleReport(CommentReportHandleDTO handleDTO) {
        // 1. 校验参数
        if (handleDTO.getReportId() == null) {
            throw new ContentFailedException("举报记录 ID 不能为空");
        }
        if (handleDTO.getHandleResult() == null ||
                handleDTO.getHandleResult() < 1 || handleDTO.getHandleResult() > 4) {
            throw new ContentFailedException("处理结果不合法（1-删除评论 2-警告用户 3-删除+警告 4-驳回举报）");
        }

        // 2. 查询举报记录是否存在
        CommentReport report = commentReportMapper.getReportById(handleDTO.getReportId());
        if (report == null) {
            throw new ContentFailedException("举报记录不存在");
        }

        // 3. 更新举报处理信息
        CommentReport updateReport = new CommentReport();
        updateReport.setId(handleDTO.getReportId());
        updateReport.setStatus(2); // 已处理
        updateReport.setHandlerId(BaseContext.getCurrentId()); // 当前管理员 ID
        updateReport.setHandleResult(handleDTO.getHandleResult());
        updateReport.setHandleRemark(handleDTO.getHandleRemark());
        updateReport.setHandleTime(LocalDateTime.now());
        updateReport.setUpdateTime(LocalDateTime.now());
        commentReportMapper.updateReport(updateReport);

        // 4. 如果处理结果包含删除评论（1 或 3），执行删除
        if (handleDTO.getHandleResult() == 1 || handleDTO.getHandleResult() == 3) {
            adminCommentService.deleteComment(report.getCommentId());
            log.info("处理评论举报：已删除评论，reportId={}, commentId={}", handleDTO.getReportId(), report.getCommentId());
        }

        log.info("处理评论举报成功，reportId={}, handleResult={}", handleDTO.getReportId(), handleDTO.getHandleResult());
        // ========== 新增：举报处理结果通知 ==========
        String handleResultText = switch (handleDTO.getHandleResult()) {
            case 1 -> "你举报的评论已被删除";
            case 2 -> "你举报的评论已处理，已警告用户";
            case 3 -> "你举报的评论已处理，已删除并警告用户";
            case 4 -> "你举报的评论经核实无需处理";
            default -> "你举报的评论已处理";
        };
        if (handleDTO.getHandleRemark() != null && !handleDTO.getHandleRemark().isEmpty()) {
            handleResultText += "，备注：" + handleDTO.getHandleRemark();
        }

        NotificationEventMessage reportNotification = NotificationEventMessage.builder()
                .recipientUserId(report.getReporterId())
                .actorUserId(null) // 系统通知
                .type(NotificationType.COMMENT_REPORT_RESULT.getCode())
                .content(handleResultText)
                .payload(Map.of(
                        "commentId", report.getCommentId(),
                        "reportId", handleDTO.getReportId(),
                        "handleResult", handleDTO.getHandleResult()
                ))
                .build();
        // 举报处理状态和结果通知 Outbox 在同一个事务中提交。
        notificationEventProducer.createNotificationEvent(reportNotification, ModerationTargetType.COMMENT.name(), report.getCommentId());

        // 审计：评论举报处理成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.REPORT_HANDLE,
                "REPORT",
                String.valueOf(handleDTO.getReportId()),
                "reportStatus=PENDING",
                "reportStatus=HANDLED"
        );
    }
}
