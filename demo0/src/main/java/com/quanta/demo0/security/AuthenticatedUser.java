package com.quanta.demo0.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 已通过 Token 认证的当前用户。
 *
 * 注意：
 * 这个对象只保存安全判断需要的最小信息，
 * 不保存身份证号、微信 code 等敏感数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthenticatedUser {

    /**
     * 当前用户 ID。
     */
    private Long userId;

    /**
     * 当前用户角色。
     *
     * USER和VERIFIED_USER根据用户状态生成，
     * 管理角色从user_role表加载。
     */
    private Set<String> roles;

    /**
     * 当前用户拥有的具体业务权限。
     *
     * 根据用户角色和RolePermissionMapping计算生成。
     */
    private Set<String> authorities;

    /**
     * 账号状态：0正常，1封禁。
     */
    private Integer accountStatus;

    /**
     * 是否已经通过校友身份认证。
     */
    private Boolean verified;

    /**
     * 是否为超级管理员。
     *
     * 根据user_role表中的SUPER_ADMIN角色计算，
     * 不再直接信任JWT或旧is_admin字段。
     */
    private Boolean admin;
}