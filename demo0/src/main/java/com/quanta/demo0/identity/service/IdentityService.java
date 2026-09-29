package com.quanta.demo0.identity.service;

import com.quanta.demo0.identity.dto.UserAuthDTO;
import com.quanta.demo0.identity.entity.UserAuth;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;

/**
 * 用户身份认证服务，负责用户侧认证申请和认证状态读取。
 *
 * 管理端审核由 IdentityExamService 负责；本接口只覆盖用户侧公开路径。
 */
public interface IdentityService {

    /**
     * 提交或重新提交身份认证申请。
     *
     * @param userAuthDTO 认证申请
     * @return 持久化后的认证记录
     */
    UserAuth addUserAuth(UserAuthDTO userAuthDTO);

    /**
     * 查询用户认证状态。
     *
     * @param currentId 当前用户 ID
     * @return 认证状态
     */
    UserAuthStatusVO getAuthStatus(Long currentId);

    /**
     * 查询已通过认证的详情。
     *
     * @param currentId 当前用户 ID
     * @return 认证详情
     */
    UserAuth getAuthDetail(Long currentId);
}
