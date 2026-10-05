package com.quanta.demo0.identity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证状态出参（GET /user/auth/status），也被 platform.security 的登录快照复用：
 * AuthenticationSnapshotCacheImpl 拿它判断 auditStatus 是否为"已通过"，
 * 通过才给用户挂 VERIFIED_USER 角色——发帖/评论/答题接口的 @PreAuthorize 全指望它。
 *
 * 【坑】auditStatus 用的是 AuditStatus 码值（-1/0/1/2），与 tb_user.auth_status
 * 的展示态（UserAuthDisplayStatus 0/1/2/3）是两套编码，见枚举注释。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserAuthStatusVO {

    private Integer auditStatus;//-1-未认证过 0-待审核 1-审核通过 2-审核驳回
    private String auditRemark;//审核原因
}
