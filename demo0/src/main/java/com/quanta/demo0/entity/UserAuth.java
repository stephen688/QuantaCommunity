package com.quanta.demo0.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户认证信息
 * */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserAuth implements Serializable {


    /**
     * 认证信息实体类
     */

        /**
         * 认证记录ID（主键）
         */
        private Long authId;

        /**
         * 关联用户ID（唯一）
         */
        private Long userId;

        /**
         * 身份类型：1-在校成员 2-历届校友
         */
        private Integer identityType;

        /**
         * 真实姓名
         */
        private String realName;

        /**
         * 学号/校友编号
         */
        private String schoolId;

        /**
         * Quanta届数
         */
        private String quantaBatch;

        /**
         * 所属部门
         */
        private String quantaDepartment;

        /**
         * 审核状态：0-待审核 1-已通过 2-已驳回
         */
        private Integer auditStatus;

        /**
         * 审核备注/驳回原因
         */
        private String auditRemark;

        /**
         * 审核时间
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime auditTime;

        /**
         * 申请提交时间
         */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime;

        /**
         * 更新时间
         */
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime updateTime;
    }


