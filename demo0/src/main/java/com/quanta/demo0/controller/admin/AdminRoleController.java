package com.quanta.demo0.controller.admin;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AdminRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 角色管理控制器。
 */
@RestController
@RequestMapping("/admin/roles")
@Slf4j
public class AdminRoleController {

    @Autowired
    private AdminRoleService adminRoleService;

    @AdminAudit(
            action = AdminAuditActionConstants.ROLE_GRANT,
            targetType = "USER_ROLE",
            targetId = "#userId + ':' + #roleCode"
    )

    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_MANAGE + "')")
    @PostMapping("/{userId}/{roleCode}")
    public Result grantRole(
            @PathVariable Long userId,
            @PathVariable String roleCode
    ) {
        log.info("管理端授予角色，userId={}, roleCode={}", userId, roleCode);
        adminRoleService.grantRole(userId, roleCode);
        return Result.success();
    }

    @AdminAudit(
            action = AdminAuditActionConstants.ROLE_REVOKE,
            targetType = "USER_ROLE",
            targetId = "#userId + ':' + #roleCode"
    )

    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_MANAGE + "')")
    @DeleteMapping("/{userId}/{roleCode}")
    public Result revokeRole(
            @PathVariable Long userId,
            @PathVariable String roleCode
    ) {
        log.info("管理端撤销角色，userId={}, roleCode={}", userId, roleCode);
        adminRoleService.revokeRole(userId, roleCode);
        return Result.success();
    }
}
