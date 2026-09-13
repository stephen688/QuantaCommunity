package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
    private String avatarUrl;

    /**
     * 用户名
     */
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
