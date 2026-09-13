package com.quanta.demo0.controller.admin;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.dto.ContentAdminQueryDTO;
import com.quanta.demo0.dto.ContentAuditDTO;
import com.quanta.demo0.dto.ContentReportHandleDTO;
import com.quanta.demo0.dto.ContentReportQueryDTO;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AdminContentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 内容管理控制器
 * 路径前缀：/admin/content
 */
@RestController
@RequestMapping("/admin/content")
@Slf4j
public class AdminContentController {

    @Autowired
    private AdminContentService adminContentService;

    /**
     * 分页查询内容列表
     *
     * 接口说明：
     * - 路径：GET /admin/content/page
     * - 权限：仅管理员可访问
     * - 支持按审核状态、内容类型筛选
     *
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - auditStatus：可选，审核状态（0-待审核 1-已通过 2-已驳回）
     * - contentType：可选，内容类型（1-生活求助 2-专业问答）

     * 返回数据：
     * - total：总记录数
     * - records：当前页内容列表（Content 实体）
     * 示例请求：
     * GET /admin/content/page?pageNum=1&pageSize=10&auditStatus=0
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/page")
    public Result<PageResult> page(ContentAdminQueryDTO query) {
        log.info("管理端分页查询内容，查询条件：{}", query);
        PageResult pageResult = adminContentService.pageQuery(query);
        return Result.success(pageResult);
    }



    /**
     * 审核帖子
     * 接口说明：
     * - 路径：POST /admin/content/audit
     * - 权限：仅管理员可访问
     * - 审核通过后：同步到 ES 和向量库
     * - 审核驳回后：从 ES 和向量库删除
     * 请求体（JSON）：
     * - contentId：内容 ID
     * - auditResult：审核结果（1-通过 2-驳回）
     * - rejectReason：驳回说明（可选，仅驳回时填写）
     * 示例请求：
     * POST /admin/content/audit
     * {
     *   "contentId": 1,
     *   "auditResult": 1
     * }
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_AUDIT,
            targetType = "CONTENT",
            targetId = "#auditDTO.contentId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "')")
    @PostMapping("/audit")
    public Result audit(@RequestBody ContentAuditDTO auditDTO) {
        log.info("管理端审核帖子，审核信息：{}", auditDTO);
        adminContentService.audit(auditDTO);
        return Result.success();
    }
    /**
     * 删除帖子
     * 接口说明：
     * - 路径：DELETE /admin/content/{contentId}
     * - 权限：仅管理员可访问
     * - 删除后：软删除内容，清理 Redis、ES、向量库、Feed 流
     * 路径参数：
     * - contentId：内容 ID
     * 示例请求：
     * DELETE /admin/content/1
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_DELETE,
            targetType = "CONTENT",
            targetId = "#contentId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_DELETE + "')")
    @DeleteMapping("/{contentId}")
    public Result delete(@PathVariable Long contentId) {
        log.info("管理端删除帖子，contentId={}", contentId);
        adminContentService.deleteContent(contentId);
        return Result.success();
    }

    /**
     * 分页查询帖子举报列表
     * 接口说明：
     * - 路径：GET /admin/content/report/page
     * - 权限：仅管理员可访问
     * - 支持按处理状态筛选
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - status：可选，处理状态（0-待处理 1-处理中 2-已处理 3-已驳回）
     * 返回数据：
     * - total：总记录数
     * - records：当前页举报列表（ContentReport 实体）
     * 示例请求：
     * GET /admin/content/report/page?pageNum=1&pageSize=10&status=0
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/report/page")
    public Result<PageResult> pageReport(ContentReportQueryDTO query) {
        log.info("管理端分页查询帖子举报，查询条件：{}", query);
        PageResult pageResult = adminContentService.pageReport(query);
        return Result.success(pageResult);
    }


    /**
     * 处理帖子举报
     * 接口说明：
     * - 路径：POST /admin/content/report/handle
     * - 权限：仅管理员可访问
     * - 处理结果：1-删除帖子 2-警告用户 3-删除+警告 4-驳回举报
     * - 如果选择删除帖子，会自动执行删除操作
     * 请求体（JSON）：
     * - reportId：举报记录 ID
     * - handleResult：处理结果（1-删除帖子 2-警告用户 3-删除+警告 4-驳回举报）
     * - handleRemark：处理备注（可选）
     * 示例请求：
     * POST /admin/content/report/handle
     * {
     *   "reportId": 1,
     *   "handleResult": 1,
     *   "handleRemark": "内容违规，已删除"
     * }
     */
    @AdminAudit(
            action = AdminAuditActionConstants.REPORT_HANDLE,
            targetType = "REPORT",
            targetId = "#handleDTO.reportId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "')")
    @PostMapping("/report/handle")
    public Result handleReport(@RequestBody ContentReportHandleDTO handleDTO) {
        log.info("管理端处理帖子举报，处理信息：{}", handleDTO);
        adminContentService.handleReport(handleDTO);
        return Result.success();
    }
}
