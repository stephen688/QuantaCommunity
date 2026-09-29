package com.quanta.demo0.platform.security.service;

import com.quanta.demo0.user.entity.User;

/**
 * 会话服务，负责 JWT 会话签发、Redis 会话保存和退出登录。
 *
 * 用户账号由 user 域解析；本服务只接收已解析的 User，不访问 UserMapper，
 * 从而保持 platform/security 对 user 域的最小依赖。
 */
public interface SessionService {

    /**
     * 为账号签发登录令牌并写入 Redis。
     *
     * @param user 已登录的用户账号
     * @return JWT 令牌
     */
    String login(User user);

    /**
     * 删除当前会话及其封禁标记。
     */
    void logout();
}
