package com.quanta.demo0.platform.security.service;

/**
 * 管理端角色管理服务。
 */
public interface AdminRoleService {

    void grantRole(Long userId, String roleCode);

    void revokeRole(Long userId, String roleCode);
}
