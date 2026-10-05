package com.quanta.demo0.identity.service;

import com.quanta.demo0.identity.vo.UserAuthStatusVO;

/**
 * 身份认证事实查询端口。
 *
 * 职责：向 user、安全等调用方公开认证状态；
 * 边界：不暴露 UserAuth 持久化实体，不承担认证申请和审核写入。
 */

/**
 * （补充）这是 identity 域对外开放的"事实读端口"，有两类跨域消费方：
 * 1. platform.security 的 AuthenticationSnapshotCacheImpl——组装登录快照时，
 *    查到 auditStatus=已通过才给用户挂 VERIFIED_USER 角色（发帖/评论/答题的
 *    @PreAuthorize 靠这个角色放行）；
 * 2. user 域的 UserProfileServiceImpl——个人主页的认证徽章用它核对
 *    tb_user.auth_status 投影是否过期，不一致时自动回写（自愈）。
 * **认证能不能"用"，全看这个查询的结果。**
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
