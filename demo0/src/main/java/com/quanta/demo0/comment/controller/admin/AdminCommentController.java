package com.quanta.demo0.comment.controller.admin;

import com.quanta.demo0.platform.audit.annotation.AdminAudit;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.comment.dto.CommentAdminQueryDTO;
import com.quanta.demo0.comment.dto.CommentAuditDTO;
import com.quanta.demo0.interaction.dto.CommentReportHandleDTO;
import com.quanta.demo0.interaction.dto.CommentReportQueryDTO;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.comment.service.AdminCommentService;
import com.quanta.demo0.interaction.service.ReportGovernanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 评论管理控制器
 * 路径前缀：/admin/comment
 *
 * ============================================================
 * 【为什么 B 端接口全部用 hasAuthority（权限点）而不是 C 端的 hasRole（角色）？】
 * ============================================================
 * C 端判断"你是哪类人"（VERIFIED_USER / BOT 角色），B 端判断"你被授予了哪个权限点"
 * （PermissionConstants 里的 CONTENT_READ_ADMIN / CONTENT_DELETE / CONTENT_AUDIT）。
 * **角色是身份，权限是能力**：管理员内部也分档，用权限点才能做到
 * "能看的不能删、能删的不能审"，且调整授权不必改代码。
 * 同时，三个写操作（删除 / 审核 / 处理举报）都叠加了 @AdminAudit 注解，
 * 操作成功后由 AdminAuditRecorder 写入审计日志——治理动作必须留痕。
 */
@RestController
@RequestMapping("/admin/comment")
@Slf4j
public class AdminCommentController {

    @Autowired
    private AdminCommentService adminCommentService;
    @Autowired
    private ReportGovernanceService reportGovernanceService;

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
     *
     * 【与 C 端列表的本质区别】管理端查询不过滤 audit_status（mapper 的 pageAdmin
     * 只固定 is_deleted=0），因为审核员要看到待审 / 已驳回的评论；
     * C 端 /comment/list 则强制 audit_status=1 只露可见内容。
     * 返回的 records 直接是 ContentComment 实体——B 端表格需要全部审核字段
     * （rejectReason / auditTime / auditUserId），所以这里不像 C 端那样裁剪成 VO。
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
     *
     * 【管理删除 vs 用户删除】C 端删除需要"本人 / 题主 / 答主"身份，管理员删除
     * 只凭 CONTENT_DELETE 权限点，无需归属校验；且 C 端评论计数走 CommentCounterService，
     * 管理端直接调 ContentCounterService / AnswerCounterService ——
     * 两条删除路径都要把删除动作与热度、搜索 Outbox 放进同一事务（见 AdminCommentServiceImpl.deleteComment）。
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
     *
     * 【状态机】实际允许的流转有四条（见 AdminCommentServiceImpl.auditComment）：
     * 待审→通过、待审→驳回、已通过→驳回（回滚计数）、已驳回→重新通过（补计数）。
     * 状态变更用"条件 UPDATE"（updateAuditStatusIfCurrent：WHERE audit_status = 旧值），
     * **管理员与 AI 并发审核时只有一方能成功**，另一方拿到 0 行更新、收到
     * "请刷新后重试"的 400，绝不会互相覆盖审核结果。
     * 审核人 ID 同样从 BaseContext（token）取，不信任请求体。
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
     *
     * 【归属说明】举报数据本体在 interaction 包（CommentReport），
     * 处置逻辑在 ReportGovernanceService——评论管理控制器只是把举报治理
     * 聚合到同一个后台入口，方便审核员在一个页面里完成"看评论 + 看举报"。
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/report/page")
    public Result<PageResult> pageReport(CommentReportQueryDTO query) {
        log.info("管理端分页查询评论举报，查询条件：{}", query);
        PageResult pageResult = reportGovernanceService.pageReport(query);
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
     *
     * 【闭环】选择"删除评论"（1 或 3）时会真正执行上文 DELETE 的删除链路，
     * 举报单同步更新为已处理；@AdminAudit 把 reportId 与处置结果写入审计日志，
     * 保证每个治理决定都可追溯到具体管理员。
     */
    @AdminAudit(
            action = AdminAuditActionConstants.REPORT_HANDLE,
            targetType = "REPORT",
            targetId = "#handleDTO.reportId"
    )
    // 复合处置包含删除时，必须同时通过独立删除权限，前端选项过滤不能代替后端授权。
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "') and "
            + "((#handleDTO.handleResult != 1 and #handleDTO.handleResult != 3) or "
            + "hasAuthority('" + PermissionConstants.CONTENT_DELETE + "'))")
    @PostMapping("/report/handle")
    public Result handleReport(@RequestBody CommentReportHandleDTO handleDTO) {
        log.info("管理端处理评论举报，处理信息：{}", handleDTO);
        reportGovernanceService.handleReport(handleDTO);
        return Result.success();
    }
}
