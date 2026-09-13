package com.quanta.demo0.service.Impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.dto.CommentAdminQueryDTO;
import com.quanta.demo0.dto.CommentAuditDTO;
import com.quanta.demo0.dto.CommentReportHandleDTO;
import com.quanta.demo0.dto.CommentReportQueryDTO;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.entity.CommentReport;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.service.AdminAuditRecorder;
import com.quanta.demo0.service.AdminCommentService;
import com.quanta.demo0.service.CommentAuditService;
import com.quanta.demo0.service.OutboxEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端评论服务实现类。
 *
 * 核心职责：
 * 1. 提供评论分页查询、人工审核、删除、举报处理等后台能力；
 * 2. 协调评论审核副作用（计数、热度、索引、通知）与状态回滚；
 * 3. 维护举报单处理状态，形成可追踪的内容治理闭环。
 *
 * 设计说明：
 * - 审核与删除采用事务控制，确保评论状态与关联数据一致；
 * - 复用 CommentAuditService，保证 AI 审核与人工审核行为一致。
 */
@Service
@Slf4j
public class AdminCommentServiceImpl implements AdminCommentService {
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private OutboxEventService outboxEventService;
    @Autowired
    private CommentAuditService commentAuditService;

    @Autowired
    private AdminAuditRecorder adminAuditRecorder;

    /**
     * 分页查询评论列表
     * 执行流程：
     * 1. PageHelper.startPage() 开启分页
     * 2. 调用 Mapper 执行 SQL 查询
     * 3. 封装为 PageResult 返回
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @Override
    public PageResult pageQuery(CommentAdminQueryDTO query) {
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<ContentComment> page = commentMapper.pageAdmin(query);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 管理端人工审核评论
     * - 待审 → 通过：执行 CommentAuditService 可见性副作用
     * - 待审 → 驳回：更新状态并记录审核人
     * - 通过 ↔ 驳回：回滚或补执行计数与索引
     */
    @Override
    @Transactional
    public void auditComment(CommentAuditDTO auditDTO) {
        if (auditDTO.getCommentId() == null) {
            throw new ContentFailedException("评论 ID 不能为空");
        }
        if (auditDTO.getAuditResult() == null
                || (auditDTO.getAuditResult() != 1 && auditDTO.getAuditResult() != 2)) {
            throw new ContentFailedException("审核结果不合法（1-通过 2-驳回）");
        }

        ContentComment comment = commentMapper.selectById(auditDTO.getCommentId());
        if (comment == null) {
            throw new ContentFailedException("评论不存在");
        }

        Integer oldAuditStatus = comment.getAuditStatus();
        if (oldAuditStatus != null && oldAuditStatus.equals(auditDTO.getAuditResult())) {
            return;
        }

        Long adminId = BaseContext.getCurrentId();

        boolean updated;
        if (AuditStatus.PENDING.getCode().equals(oldAuditStatus) && auditDTO.getAuditResult() == 1) {
            updated = commentAuditService.approveComment(auditDTO.getCommentId(), adminId);
        } else if (AuditStatus.PENDING.getCode().equals(oldAuditStatus) && auditDTO.getAuditResult() == 2) {
            updated = commentAuditService.rejectComment(auditDTO.getCommentId(), auditDTO.getRejectReason(), adminId);
        } else if (AuditStatus.APPROVED.getCode().equals(oldAuditStatus) && auditDTO.getAuditResult() == 2) {
            updated = commentAuditService.revertApprovedComment(auditDTO.getCommentId(), auditDTO.getRejectReason(), adminId);
        } else if (AuditStatus.REJECTED.getCode().equals(oldAuditStatus) && auditDTO.getAuditResult() == 1) {
            updated = commentAuditService.approveRejectedComment(auditDTO.getCommentId(), adminId);
        } else {
            throw new ContentFailedException("不支持的审核状态变更");
        }

        // 查询后如果 AI 抢先改变了状态，条件更新会返回 0，提示管理员刷新。
        if (!updated) {
            throw new ContentFailedException("评论状态已被其他审核操作修改，请刷新后重试");
        }

        log.info("管理端审核评论成功，commentId={}, oldStatus={}, newStatus={}",
                auditDTO.getCommentId(), oldAuditStatus, auditDTO.getAuditResult());

        // 审计：评论审核成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.CONTENT_AUDIT,
                "COMMENT",
                String.valueOf(auditDTO.getCommentId()),
                "auditStatus=" + oldAuditStatus,
                "auditStatus=" + auditDTO.getAuditResult()
        );
    }

    /**
     * 管理端删除评论
     * 执行流程：
     * 1. 参数校验
     * 2. 查询评论是否存在
     * 3. 物理删除评论图片
     * 4. 物理删除评论点赞记录
     * 5. 软删除回复评论（子评论）
     * 6. 物理删除回复评论图片
     * 7. 物理删除回复评论点赞记录
     * 8. 软删除评论本身
     * 9. 更新帖子评论数（-1）
     * 10. 事务提交后：更新 ES 索引（评论数变化）
     *
     * @param commentId 评论 ID
     */
    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        // 1. 参数校验
        if (commentId == null) {
            throw new ContentFailedException("评论 ID 不能为空");
        }

        // 2. 查询评论是否存在
        ContentComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new ContentFailedException("评论不存在");
        }

        // 3. 物理删除评论图片
        commentMapper.deleteCommentImages(commentId);

        // 4. 物理删除评论点赞记录
        commentMapper.deleteCommentLikes(commentId);

        // 5. 软删除回复评论（子评论）
        List<Long> replyIds = commentMapper.selectReplyIdsByParentId(commentId);
        if (replyIds != null && !replyIds.isEmpty()) {
            commentMapper.softDeleteRepliesByCommentIds(replyIds);
            // 6. 物理删除回复评论图片
            commentMapper.deleteCommentImagesByCommentIds(replyIds);
            // 7. 物理删除回复评论点赞记录
            commentMapper.deleteCommentLikesByCommentIds(replyIds);
        }

        // 8. 软删除评论本身
        commentMapper.softDeleteById(commentId);

        // 9. 更新帖子评论数
        if (comment.getAnswerId() != null) {
            // 回答下的评论：更新回答评论数
            int replyCount = replyIds != null ? replyIds.size() : 0;
            questionMapper.updateAnswerCommentCount(comment.getAnswerId(), -(1 + replyCount));
        } else {
            // 帖子下的一级评论：更新帖子评论数
            int replyCount = replyIds != null ? replyIds.size() : 0;
            commentMapper.updateCommentCount(comment.getContentId(), -(1 + replyCount));
        }

        // 管理员删除评论和热度 Outbox 在同一个事务中提交。
        outboxEventService.createHotScoreRecalculateEvent(comment.getContentId(), "COMMENT_DELETE");
        // 评论数变化后按 MySQL 最新值重建帖子索引；回答下的评论还要重建回答索引。
        outboxEventService.createSearchReconcileEvent(ModerationTargetType.CONTENT.name(), comment.getContentId(), "COMMENT_DELETE");
        if (comment.getAnswerId() != null) {
            outboxEventService.createSearchReconcileEvent(ModerationTargetType.ANSWER.name(), comment.getAnswerId(), "COMMENT_DELETE");
        }
        log.info("管理端删除评论成功，commentId={}, contentId={}", commentId, comment.getContentId());

        // 审计：评论删除成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.CONTENT_DELETE,
                "COMMENT",
                String.valueOf(commentId),
                "deleted=0",
                "deleted=1"
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
        Page<CommentReport> page = commentMapper.pageReport(query);
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
        CommentReport report = commentMapper.getReportById(handleDTO.getReportId());
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
        commentMapper.updateReport(updateReport);

        // 4. 如果处理结果包含删除评论（1 或 3），执行删除
        if (handleDTO.getHandleResult() == 1 || handleDTO.getHandleResult() == 3) {
            deleteComment(report.getCommentId());
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
        outboxEventService.createNotificationEvent(reportNotification, ModerationTargetType.COMMENT.name(), report.getCommentId());

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
