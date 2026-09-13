package com.quanta.demo0.controller.admin;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.dto.CommentAdminQueryDTO;
import com.quanta.demo0.dto.CommentAuditDTO;
import com.quanta.demo0.dto.CommentReportHandleDTO;
import com.quanta.demo0.dto.CommentReportQueryDTO;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AdminCommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 评论管理控制器
 * 路径前缀：/admin/comment
 */
@RestController
@RequestMapping("/admin/comment")
@Slf4j
public class AdminCommentController {

    @Autowired
    private AdminCommentService adminCommentService;

    /**
     * 分页查询评论列表
     *
     * 接口说明：
     * - 路径：GET /admin/comment/page
     * - 权限：仅管理员可访问
     * - 支持按帖子 ID 筛选
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - contentId：可选，帖子 ID
     * - auditStatus：可选，审核状态（0-待审核 1-已通过 2-已驳回）
     * 返回数据：
     * - total：总记录数
     * - records：当前页评论列表（ContentComment 实体）
     * 示例请求：
     * GET /admin/comment/page?pageNum=1&pageSize=10&contentId=1
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/page")
    public Result<PageResult> page(CommentAdminQueryDTO query) {
        log.info("管理端分页查询评论，查询条件：{}", query);
        PageResult pageResult = adminCommentService.pageQuery(query);
        return Result.success(pageResult);
    }


    /**
     * 删除评论
     * 接口说明：
     * - 路径：DELETE /admin/comment/{commentId}
     * - 权限：仅管理员可访问
     * - 删除后：软删除评论及回复，清理图片、点赞记录，同步 ES
     * 路径参数：
     * - commentId：评论 ID
     * 示例请求：
     * DELETE /admin/comment/1
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_DELETE,
            targetType = "COMMENT",
            targetId = "#commentId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_DELETE + "')")
    @DeleteMapping("/{commentId}")
    public Result delete(@PathVariable Long commentId) {
        log.info("管理端删除评论，commentId={}", commentId);
        adminCommentService.deleteComment(commentId);
        return Result.success();
    }

    /**
     * 审核评论（人工处理 AI MANUAL 待审评论）
     * - 路径：POST /admin/comment/audit
     * - auditResult：1-通过 2-驳回
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_AUDIT,
            targetType = "COMMENT",
            targetId = "#auditDTO.commentId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "')")
    @PostMapping("/audit")
    public Result audit(@RequestBody CommentAuditDTO auditDTO) {
        log.info("管理端审核评论，审核信息：{}", auditDTO);
        adminCommentService.auditComment(auditDTO);
        return Result.success();
    }


    /**
     * 分页查询评论举报列表
     * 接口说明：
     * - 路径：GET /admin/comment/report/page
     * - 权限：仅管理员可访问
     * - 支持按处理状态筛选
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - status：可选，处理状态（0-待处理 1-处理中 2-已处理 3-已驳回）
     * 返回数据：
     * - total：总记录数
     * - records：当前页举报列表（CommentReport 实体）
     * 示例请求：
     * GET /admin/comment/report/page?pageNum=1&pageSize=10&status=0
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/report/page")
    public Result<PageResult> pageReport(CommentReportQueryDTO query) {
        log.info("管理端分页查询评论举报，查询条件：{}", query);
        PageResult pageResult = adminCommentService.pageReport(query);
        return Result.success(pageResult);
    }

    /**
     * 处理评论举报
     * 接口说明：
     * - 路径：POST /admin/comment/report/handle
     * - 权限：仅管理员可访问
     * - 处理结果：1-删除评论 2-警告用户 3-删除+警告 4-驳回举报
     * - 如果选择删除评论，会自动执行删除操作
     * 请求体（JSON）：
     * - reportId：举报记录 ID
     * - handleResult：处理结果（1-删除评论 2-警告用户 3-删除+警告 4-驳回举报）
     * - handleRemark：处理备注（可选）
     * 示例请求：
     * POST /admin/comment/report/handle
     * {
     *   "reportId": 1,
     *   "handleResult": 1,
     *   "handleRemark": "评论违规，已删除"
     * }
     */
    @AdminAudit(
            action = AdminAuditActionConstants.REPORT_HANDLE,
            targetType = "REPORT",
            targetId = "#handleDTO.reportId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "')")
    @PostMapping("/report/handle")
    public Result handleReport(@RequestBody CommentReportHandleDTO handleDTO) {
        log.info("管理端处理评论举报，处理信息：{}", handleDTO);
        adminCommentService.handleReport(handleDTO);
        return Result.success();
    }
}
