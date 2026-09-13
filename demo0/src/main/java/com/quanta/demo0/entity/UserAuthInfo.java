
    package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

    /**
     * 用户认证信息（联表查询结果）
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public class UserAuthInfo implements Serializable {

        /**
         * 用户 ID
         */
        private Long userId;

        /**
         * 用户头像
         */
        private String avatarUrl;

        /**
         * 用户昵称
         */
        private String nickName;

        /**
         * 用户部门/专业
         */
        private String quantaDepartment;

        /**
         * 用户届数
         */
        private String quantaBatch;

        /**
         * 认证状态
         */
        private Integer authStatus;

        /**
         * 账号状态：0-正常 1-封禁
         */
        private Integer accountStatus;
    }

