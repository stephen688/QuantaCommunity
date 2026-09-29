package com.quanta.demo0.identity.service;

import com.quanta.demo0.identity.vo.UserAuthStatusVO;

/**
 * 身份认证事实查询端口。
 *
 * 职责：向 user、安全等调用方公开认证状态；
 * 边界：不暴露 UserAuth 持久化实体，不承担认证申请和审核写入。
 */
public interface IdentityQueryService {

    /**
     * 查询指定用户的认证状态。
     *
     * @param userId 用户 ID
     * @return 认证状态，未提交时返回 UNSUBMITTED
     */
    UserAuthStatusVO getAuthStatus(Long userId);
}
