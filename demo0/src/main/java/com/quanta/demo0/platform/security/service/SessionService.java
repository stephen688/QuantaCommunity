package com.quanta.demo0.platform.security.service;

import com.quanta.demo0.user.vo.UserAccountVO;

/**
 * 会话服务，负责 JWT 会话签发、Redis 会话保存和退出登录。
 *
 * 用户账号由 user 域解析；本服务只接收已解析的 UserAccountVO，不访问 UserMapper，
 * 从而保持 platform/security 对 user 域的最小依赖。
 */
public interface SessionService {

    /**
     * 为账号签发登录令牌并写入 Redis。
     *
     * @param user 已登录的用户账号快照
     * @return JWT 令牌
     */
    String login(UserAccountVO user);

    /**
     * 删除当前会话及其封禁标记。
     */
    void logout();
}
