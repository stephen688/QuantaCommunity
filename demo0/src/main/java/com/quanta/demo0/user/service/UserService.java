package com.quanta.demo0.user.service;



import com.quanta.demo0.identity.dto.UserAuthDTO;
import com.quanta.demo0.user.dto.UserInfoDTO;
import com.quanta.demo0.user.dto.UserLoginDTO;
import com.quanta.demo0.user.entity.User;
import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.user.vo.UserInfoVO;
import com.quanta.demo0.user.vo.UserProfileVO;

import java.util.Map;

public interface UserService {

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    User weChatLogin(UserLoginDTO userLoginDTO);

    /**
     * 添加用户认证信息
     * @param userAuthDTO
     * @return
     */
    UserAuth addUserAuth(UserAuthDTO userAuthDTO);

    /**
     * 获取用户信息
     * @param id
     * @return
     */
    UserInfoVO getById(Long id);

    /**
     * 更新用户信息
     * @param userInfoDTO
     */
    void updateUserInfo(UserInfoDTO userInfoDTO);

    /**
     * 获取用户认证状态
     *
     * @param currentId
     * @return
     */
    UserAuthStatusVO getAuthStatus(Long currentId);

    /**
     * 获取用户认证信息
     *
     * @param currentId
     * @return
     */
    UserAuth getAuthDetail(Long currentId);

    /**
     * 获取用户主页信息（C 端公开字段）
     *
     * @param targetUserId 目标用户 ID
     * @param viewerId     当前登录用户 ID（用于判断关注关系）
     * @return 用户主页信息
     */
    UserProfileVO getUserProfile(Long targetUserId, Long viewerId);


    void logout();
}
