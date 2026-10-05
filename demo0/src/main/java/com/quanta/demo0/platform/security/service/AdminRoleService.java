package com.quanta.demo0.platform.security.service;

import java.util.List;

/**
 * 管理端角色管理服务。
 */
public interface AdminRoleService {

    /**
     * 查询指定用户当前拥有的角色代码。
     *
     * @param userId 用户 ID
     * @return 用户当前角色代码列表
     * @throws RuntimeException 用户不存在或角色读取失败
     */
    List<String> getUserRoles(Long userId);

    void grantRole(Long userId, String roleCode);

    void revokeRole(Long userId, String roleCode);
}
