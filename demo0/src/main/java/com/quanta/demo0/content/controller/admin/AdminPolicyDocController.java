package com.quanta.demo0.content.controller.admin;

import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.content.dto.PolicyDocAdminQueryDTO;
import com.quanta.demo0.content.service.AdminPolicyDocService;
import com.quanta.demo0.content.vo.PolicyDocAdminVO;
import com.quanta.demo0.platform.audit.annotation.AdminAudit;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端政策源文档接口。
 *
 * <p>仅 OPERATIONS_ADMIN 可访问；接口只维护 MySQL 源文档，不直接触发 QuantaBot 付费 ingest 或声明
 * Qdrant 已更新。Bot 通过既有 update_time 水位线同步接口增量拉取变更。</p>
 */
@RestController
@RequestMapping("/admin/knowledge/policy-docs")
@RequiredArgsConstructor
public class AdminPolicyDocController {

    private final AdminPolicyDocService adminPolicyDocService;

    /** 分页查询政策源文档。 */
    @GetMapping("/page")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    public Result<PageResult> page(PolicyDocAdminQueryDTO query) {
        return Result.success(adminPolicyDocService.pageQuery(query));
    }

    /** 查询包含已删除墓碑的政策源文档详情。 */
    @GetMapping("/{docId}")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    public Result<PolicyDocAdminVO> detail(@PathVariable String docId) {
        return Result.success(adminPolicyDocService.getDetail(docId));
    }

    /** 新增、更新或明确恢复政策源文档。 */
    @PostMapping
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @AdminAudit(
            action = AdminAuditActionConstants.POLICY_DOC_UPSERT,
            targetType = "POLICY_DOC",
            targetId = "#dto.docId"
    )
    public Result<Void> upsert(@RequestBody BotPolicyDocDTO dto) {
        adminPolicyDocService.upsert(dto);
        return Result.success();
    }

    /** 软删除政策源文档，保留墓碑供下游同步删除事件。 */
    @DeleteMapping("/{docId}")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @AdminAudit(
            action = AdminAuditActionConstants.POLICY_DOC_DELETE,
            targetType = "POLICY_DOC",
            targetId = "#docId"
    )
    public Result<Void> delete(@PathVariable String docId) {
        adminPolicyDocService.softDelete(docId);
        return Result.success();
    }
}
