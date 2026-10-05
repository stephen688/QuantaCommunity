package com.quanta.demo0.comment.service;

import com.quanta.demo0.comment.dto.CommentAdminQueryDTO;
import com.quanta.demo0.comment.dto.CommentAuditDTO;
import com.quanta.demo0.platform.common.result.PageResult;

/**
 * 管理端评论接口：分页巡检、删除、人工审核。
 *
 * <p>与用户侧命令接口的差别：不做评论归属校验（权限点校验在控制器层，
 * 见 AdminCommentController 的 @PreAuthorize）、可见全部审核状态、
 * 删除与审核动作经 AdminAuditRecorder 写管理审计。实现见
 * AdminCommentServiceImpl，审核动作委托 CommentAuditService 复用同一状态机。</p>
 */
public interface AdminCommentService {

    /** 按内容 ID / 审核状态分页巡检评论（含待审、已驳回）。 */
    PageResult pageQuery(CommentAdminQueryDTO query);

    /** 删除评论并级联清理回复、图片、点赞与派生数据（写入管理审计）。 */
    void deleteComment(Long commentId);

    /**
     * 管理端人工审核评论（处理 AI MANUAL 待审评论）
     */
    void auditComment(CommentAuditDTO auditDTO);

}
