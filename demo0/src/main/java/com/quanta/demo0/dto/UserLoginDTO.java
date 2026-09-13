package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 微信用户登录DTO
 * */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLoginDTO implements Serializable {

    /**
     * 微信登录凭证（code）
     */
    private String code;

    /**
     * 用户授权后的微信昵称（小程序 getUserProfile / 头像昵称组件获得）
     */
    private String nickName;

    /**
     * 用户授权后的微信头像 URL（须为 https 临时链或已上传至 OSS 的地址）
     */
    private String avatarUrl;
}
