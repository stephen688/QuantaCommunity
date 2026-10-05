package com.quanta.demo0.identity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;


/**
 * 管理端审核列表的一行数据（tb_user_auth 单表查询，IdentityExamMapper#list 直接映射）。
 *
 * 【statusText 为什么不是库字段？】SQL 查不出中文文案，由
 * IdentityExamServiceImpl#pageQuery 在 Java 层按 AuditStatus.descByCode 统一填充，
 * **状态码 → 文案的映射只维护在枚举一处**，前端不用再各抄一份。
 */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public class IdentityExamVO implements Serializable {

        private Long authId;          // 认证记录 ID
        private Long userId;          // 用户 ID
        private String realName;       // 姓名
        private String schoolId;       // 学号
        private LocalDateTime createTime; // 申请时间
        private Integer auditStatus;   // 状态
        private String statusText;     // 状态文本 (待审核/已通过/已驳回)

    }


