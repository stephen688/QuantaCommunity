package com.quanta.demo0.security;

import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.constant.RoleConstants;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色与权限映射。
 *
 * 第一版角色权限是固定规则，
 * 暂时不增加动态角色表和权限表。
 */
public final class RolePermissionMapping {

    /**
     * 工具类不允许创建对象。
     */
    private RolePermissionMapping() {
    }


    /**
     * 判断是否为系统支持的管理角色。
     *
     * 数据库中的角色字符串不能直接全部信任，
     * 只有代码中明确配置的角色才能获得管理权限。
     */
    public static boolean isManagementRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }

        return ROLE_PERMISSIONS.containsKey(role);
    }

    /**
     * 每个管理角色默认拥有的权限。
     */
    private static final Map<String, Set<String>>
            ROLE_PERMISSIONS = Map.of(

            /*
             * 内容审核人员：
             * 可以查看和审核内容，但不能封禁用户、
             * 修改角色或重放系统事件。
             */
            RoleConstants.CONTENT_AUDITOR,
            Set.of(
                    PermissionConstants.CONTENT_READ_ADMIN,
                    PermissionConstants.CONTENT_AUDIT
            ),

            /*
             * 运营管理员：
             * 可以治理用户、审核身份和查看事件，
             * 但不能执行高风险事件重放。
             */
            RoleConstants.OPERATIONS_ADMIN,
            Set.of(
                    PermissionConstants.USER_READ_ADMIN,
                    PermissionConstants.USER_BAN,
                    PermissionConstants.IDENTITY_AUDIT,
                    PermissionConstants.EVENT_READ
            ),

            /*
             * 超级管理员：
             * 拥有第一版全部管理权限。
             */
            RoleConstants.SUPER_ADMIN,
            Set.of(
                    PermissionConstants.CONTENT_READ_ADMIN,
                    PermissionConstants.CONTENT_AUDIT,
                    PermissionConstants.CONTENT_DELETE,
                    PermissionConstants.IDENTITY_AUDIT,
                    PermissionConstants.USER_READ_ADMIN,
                    PermissionConstants.USER_BAN,
                    PermissionConstants.EVENT_READ,
                    PermissionConstants.EVENT_REPLAY,
                    PermissionConstants.AUDIT_LOG_READ,
                    PermissionConstants.ROLE_MANAGE
            )
    );

    /**
     * 根据用户拥有的角色计算全部权限。
     * 一个用户可以拥有多个管理角色，
     * 最终权限是这些角色权限的合集。
     */
    public static Set<String> permissionsFor(
            Set<String> roles
    ) {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> permissions = new HashSet<>();

        for (String role : roles) {
            Set<String> rolePermissions =
                    ROLE_PERMISSIONS.get(role);

            if (rolePermissions != null) {
                permissions.addAll(rolePermissions);
            }
        }

        return Collections.unmodifiableSet(permissions);
    }
}