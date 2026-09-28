package com.quanta.demo0.platform.security;

import com.quanta.demo0.platform.security.constant.RolePermissionMapping;


import com.quanta.demo0.platform.security.constant.RoleConstants;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOT 角色注册验证（C-5 系统账号契约）。
 * BOT 可以从 user_role 进入角色集合，但不携带管理权限。
 */
class RolePermissionMappingBotTest {

    @Test
    void bot是系统支持的角色_可从userRole进入角色集合() {
        assertTrue(RolePermissionMapping.isManagementRole(RoleConstants.BOT));
    }

    @Test
    void bot角色不带任何管理权限() {
        Set<String> permissions =
                RolePermissionMapping.permissionsFor(Set.of(RoleConstants.BOT));

        assertTrue(permissions.isEmpty());
    }

    @Test
    void bot不影响现有管理角色的权限计算() {
        Set<String> withBot = RolePermissionMapping.permissionsFor(
                Set.of(RoleConstants.BOT, RoleConstants.CONTENT_AUDITOR)
        );
        Set<String> withoutBot = RolePermissionMapping.permissionsFor(
                Set.of(RoleConstants.CONTENT_AUDITOR)
        );

        assertEquals(withoutBot, withBot);
    }
}
