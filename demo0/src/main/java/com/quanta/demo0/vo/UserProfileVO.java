package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户主页信息 VO（C 端公开字段）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserProfileVO implements Serializable {

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户头像
     */
    private String avatarUrl;

    /**
     * 认证状态：0-未认证 1-审核中 2-已认证 3-审核不通过
     */
    private Integer authStatus;

    /**
     * 用户部门/专业
     */
    private String quantaDepartment;

    /**
     * 用户届数
     */
    private String quantaBatch;

    /**
     * 已发布帖子数（审核通过的）
     */
    private Integer contentCount;

    /**
     * 关注数（该用户关注了多少人）
     */
    private Integer followingCount;

    /**
     * 粉丝数（多少人关注了该用户）
     */
    private Integer followerCount;

    /**
     * 当前用户是否已关注该用户
     */
    private Boolean isFollowed;

    /**
     * 是否是当前登录用户自己
     */
    private Boolean isSelf;
}