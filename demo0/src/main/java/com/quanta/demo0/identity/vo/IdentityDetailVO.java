package com.quanta.demo0.identity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端"认证详情"出参（GET /admin/identityExam/userAuth/detail/{authId}，需 IDENTITY_AUDIT 权限）。
 *
 * 由 IdentityExamServiceImpl#getDetailById 拼装：认证资料来自 tb_user_auth，
 * 昵称/头像来自 user 域的 UserAccountVO——管理员在一屏内同时看到"账号是谁"
 * 和"认证资料填了什么"，不用前端再发第二次请求。
 *
 * 【为什么管理端单独一个 VO？】这是全系统唯一把真实姓名 + 学号展示给审核员的入口，
 * 出参只挑审核需要的字段，而不是把账号 VO 或认证实体整个透传；
 * 用户侧永远拿不到别人的这份详情（用户自己的 /auth/detail 返回的是实体本身）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class IdentityDetailVO {

    /**
     * 认证记录 ID
     */
    private Long authId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 用户头像
     */
    // 来自 user 域 userQueryService.getAccount(userId)，非认证表字段
    private String avatarUrl;

    /**
     * 用户名
     */
    // 同上，取自用户账号资料；审核员用它核对"这个认证申请挂在哪个账号下"
    private String nickName;


    /**
     * 申请时间
     */
    private LocalDateTime createTime;

    /**
     * 真实姓名
     */
    private String realName;

    /**
     * 学号
     */
    private String schoolId;
    /**
     * 毕业年份
     */
    private String quantaBatch;

    /**
     * 所在部门
     */
    private String quantaDepartment;

    /**
     * 审核状态 (待审核/已通过/已驳回)
     */
    private Integer auditStatus;

    /**
     * 审核备注/驳回原因
     */
    private String auditRemark;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
