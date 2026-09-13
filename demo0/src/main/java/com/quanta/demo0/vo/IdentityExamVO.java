package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;


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


