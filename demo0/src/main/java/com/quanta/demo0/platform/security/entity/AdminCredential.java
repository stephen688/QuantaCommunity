package com.quanta.demo0.platform.security.entity;

import lombok.Getter;
import lombok.Setter;

/** 管理凭据持久化模型：只供安全域校验，不通过 HTTP 返回密码哈希。 */
@Getter
@Setter
public class AdminCredential {
    private Long userId;
    private String username;
    private String passwordHash;
    private Integer enabled;
}
