package com.quanta.demo0.user.service;

import com.quanta.demo0.user.dto.UserInfoDTO;
import com.quanta.demo0.user.vo.UserInfoVO;
import com.quanta.demo0.user.vo.UserProfileVO;

/**
 * 用户资料服务，负责资料维护和公开主页聚合。
 *
 * 该服务不负责登录、会话或认证申请；资料写入后的读缓存失效仍由用户域统一处理。
 */
public interface UserProfileService {

    /**
     * 查询用户基本资料。
     *
     * @param id 用户 ID
     * @return 用户基本资料
     */
    UserInfoVO getById(Long id);

    /**
     * 更新当前登录用户的昵称和头像。
     *
     * @param userInfoDTO 资料更新请求
     */
    void updateUserInfo(UserInfoDTO userInfoDTO);

    /**
     * 查询用户公开主页。
     *
     * @param targetUserId 目标用户 ID
     * @param viewerId 当前查看者 ID，可为空
     * @return 用户公开主页
     */
    UserProfileVO getUserProfile(Long targetUserId, Long viewerId);
}
