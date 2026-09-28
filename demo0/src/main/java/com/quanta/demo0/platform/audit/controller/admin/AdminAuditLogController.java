package com.quanta.demo0.platform.audit.controller.admin;

import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.platform.audit.dto.AdminAuditLogQueryDTO;
import com.quanta.demo0.platform.audit.entity.AdminAuditLog;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.audit.service.AdminAuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 审计日志查询控制器。
 *
 * 审计日志只读，不提供新增和删除接口。
 */
@RestController
@RequestMapping("/admin/audit-logs")
@Slf4j
public class AdminAuditLogController {

    @Autowired
    private AdminAuditLogService adminAuditLogService;

    /**
     * 分页查询审计日志。
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.AUDIT_LOG_READ + "')")
    @GetMapping("/page")
    public Result<PageResult> pageQuery(
            AdminAuditLogQueryDTO query
    ) {
        PageResult pageResult =
                adminAuditLogService.pageQuery(query);
        return Result.success(pageResult);
    }

    /**
     * 查询审计日志详情。
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.AUDIT_LOG_READ + "')")
    @GetMapping("/{id}")
    public Result<AdminAuditLog> detail(
            @PathVariable Long id
    ) {
        return Result.success(
                adminAuditLogService.detail(id)
        );
    }
}
