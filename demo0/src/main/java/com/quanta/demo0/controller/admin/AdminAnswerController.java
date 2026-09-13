package com.quanta.demo0.controller.admin;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.dto.AnswerAdminQueryDTO;
import com.quanta.demo0.dto.ContentAuditDTO;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AdminAnswerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 回答管理控制器
 * 路径前缀：/admin/answer
 */
@RestController
@RequestMapping("/admin/answer")
@Slf4j
public class AdminAnswerController {

    @Autowired
    private AdminAnswerService adminAnswerService;

    /**
     * 分页查询回答列表
     * 接口说明：
     * - 路径：GET /admin/answer/page
     * - 权限：仅管理员可访问
     * - 支持按帖子 ID、审核状态筛选
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - contentId：可选，帖子 ID
     * - auditStatus：可选，审核状态（0-待审核 1-已通过 2-已驳回）
     * 返回数据：
     * - total：总记录数
     * - records：当前页回答列表（QuestionAnswer 实体）
     * 示例请求：
     * GET /admin/answer/page?pageNum=1&pageSize=10&auditStatus=0
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/page")
    public Result<PageResult> page(AnswerAdminQueryDTO query) {
        log.info("管理端分页查询回答，查询条件：{}", query);
        PageResult pageResult = adminAnswerService.pageQuery(query);
        return Result.success(pageResult);
    }


    /**
     * 删除回答
     * 接口说明：
     * - 路径：DELETE /admin/answer/{answerId}
     * - 权限：仅管理员可访问
     * - 删除后：软删除回答及评论，清理图片、点赞记录
     * 路径参数：
     * - answerId：回答 ID
     * 示例请求：
     * DELETE /admin/answer/1
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_DELETE,
            targetType = "ANSWER",
            targetId = "#answerId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_DELETE + "')")
    @DeleteMapping("/{answerId}")
    public Result delete(@PathVariable Long answerId) {
        log.info("管理端删除回答，answerId={}", answerId);
        adminAnswerService.deleteAnswer(answerId);
        return Result.success();
    }


    /**
     * 审核回答
     * 接口说明：
     * - 路径：POST /admin/answer/audit
     * - 权限：仅管理员可访问
     * - 审核通过后：回答可在用户端展示
     * - 审核驳回后：回答不可见，记录驳回原因
     * 请求体（JSON）：
     * - answerId：回答 ID（推荐）
     * - contentId：回答 ID（兼容历史字段）
     * - auditResult：审核结果（1-通过 2-驳回）
     * - rejectReason：驳回说明（可选，仅驳回时填写）
     * 示例请求：
     * POST /admin/answer/audit
     * {
     *   "answerId": 1,
     *   "auditResult": 1
     * }
     */
    @AdminAudit(
            action = AdminAuditActionConstants.CONTENT_AUDIT,
            targetType = "ANSWER",
            targetId = "#auditDTO.answerId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_AUDIT + "')")
    @PostMapping("/audit")
    public Result audit(@RequestBody ContentAuditDTO auditDTO) {
        log.info("管理端审核回答，审核信息：{}", auditDTO);
        adminAnswerService.auditAnswer(auditDTO);
        return Result.success();
    }
}
