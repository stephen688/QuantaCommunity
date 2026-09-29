package com.quanta.demo0.user.service;

import com.quanta.demo0.user.dto.UserLoginDTO;
import com.quanta.demo0.user.entity.User;

/**
 * 用户账号服务，负责微信登录和账号状态读取。
 *
 * 账号状态方法保持最小读取端口，供 platform/security 跨域使用；登录资料同步
 * 仍由用户域在此处完成，避免安全层反向依赖用户持久化实现。
 */
public interface UserAccountService {

    /**
     * 通过微信登录凭证获取或创建用户账号。
     *
     * @param userLoginDTO 微信登录请求
     * @return 登录后的用户账号
     */
    User weChatLogin(UserLoginDTO userLoginDTO);

    /**
     * 读取账号封禁状态。
     *
     * @param userId 用户 ID
     * @return 0 表示正常，1 表示封禁，用户不存在时返回 null
     */
    Integer getAccountStatus(Long userId);
}
