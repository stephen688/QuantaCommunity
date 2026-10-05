package com.quanta.demo0.comment.service;

/**
 * 评论审核服务：审核通过后执行计数、热度、ES、通知等副作用
 *
 * 【三类调用方，一个状态机】moderation 工作流（机审 PASS/REJECT 回调，
 * 见 ModerationWorkflowServiceImpl.dispatchCommentDecision）、发布链路
 * （机审关闭且 disabled-policy=APPROVED 时同事务自动过审，见
 * CommentCommandServiceImpl.sendComment）、管理端（人工审核/翻案/撤回，
 * 见 AdminCommentServiceImpl）。所有迁移都是 CAS 条件更新
 * （updateAuditStatusIfCurrent）：并发调用只有一个赢家，返回 false
 * 表示"已被别人处理"而非错误。
 */
public interface CommentAuditService {

    /**
     * 审核通过：更新状态并执行评论可见性副作用
     */
    boolean approveComment(Long commentId);

    /**
     * 管理端审核通过，可记录审核人 ID。
     */
    boolean approveComment(Long commentId, Long auditUserId);

    /**
     * 审核驳回：仅当仍为待审时更新状态
     */
    boolean rejectComment(Long commentId, String rejectReason);

    /**
     * 管理端人工驳回（可带审核人 ID）
     */
    boolean rejectComment(Long commentId, String rejectReason, Long auditUserId);

    /**
     * 管理端：已驳回评论重新通过，执行可见性副作用
     */
    boolean approveRejectedComment(Long commentId, Long auditUserId);

    /**
     * 管理端：已通过评论改驳回，回滚计数与索引
     */
    boolean revertApprovedComment(Long commentId, String rejectReason, Long auditUserId);
}
