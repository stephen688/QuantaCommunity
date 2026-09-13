package com.quanta.demo0.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
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
public class User implements Serializable {
    /**
     * 用户唯一ID（主键）
     */
    private Long id;

    /**
     * 微信小程序/公众号唯一标识
     */
    private String openid;


    /**
     * APP端唯一用户标识（预留）
     */
    private String appUserId;


    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户头像链接
     */
    private String avatarUrl;

    /**
     * Quanta认证状态：0-未认证 1-审核中 2-已认证 3-审核不通过
     */
    private Integer authStatus;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
    /**
          * 软删除状态：0-未删除 1-已删除
     */
    private Integer isDeleted;

    /**
     * 是否管理员：0-否 1-是
     */
    private Integer isAdmin;

    /**
     * 账号状态：0-正常 1-封禁
     */
    private Integer accountStatus;


}
