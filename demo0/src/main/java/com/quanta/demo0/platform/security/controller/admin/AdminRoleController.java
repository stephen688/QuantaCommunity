package com.quanta.demo0.platform.security.controller.admin;

import com.quanta.demo0.platform.audit.annotation.AdminAudit;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.security.service.AdminRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理端 - 角色管理控制器。
 */
@RestController
@RequestMapping("/admin/roles")
@Slf4j
public class AdminRoleController {

    @Autowired
    private AdminRoleService adminRoleService;

    /**
     * 查询指定用户的当前角色。
     *
     * @param userId 用户 ID
     * @return 当前角色代码列表
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_READ_ADMIN + "')")
    @GetMapping("/user/{userId}")
    public Result<List<String>> getUserRoles(@PathVariable Long userId) {
        log.info("管理端查询用户角色，userId={}", userId);
        return Result.success(adminRoleService.getUserRoles(userId));
    }

    @AdminAudit(
            action = AdminAuditActionConstants.ROLE_GRANT,
            targetType = "USER_ROLE",
            targetId = "#userId + ':' + #roleCode"
    )


    /**
     * 授权予用户角色。
     */
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

    /**
     * 撤销用户角色。
     * @param userId
     * @param roleCode
     * @return
     */
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
