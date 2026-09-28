package com.quanta.demo0.service;

import com.quanta.demo0.comment.dto.CommentAdminQueryDTO;
import com.quanta.demo0.comment.dto.CommentAuditDTO;
import com.quanta.demo0.dto.CommentReportHandleDTO;
import com.quanta.demo0.dto.CommentReportQueryDTO;
import com.quanta.demo0.platform.common.result.PageResult;

public interface AdminCommentService {
    PageResult pageQuery(CommentAdminQueryDTO query);

    void deleteComment(Long commentId);

    /**
     * 管理端人工审核评论（处理 AI MANUAL 待审评论）
     */
    void auditComment(CommentAuditDTO auditDTO);

    PageResult pageReport(CommentReportQueryDTO query);

    void handleReport(CommentReportHandleDTO handleDTO);
}
