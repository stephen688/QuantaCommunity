package com.quanta.demo0.user.service;

/**
 * 用户账号状态端口，供安全等跨域能力读取最小必要信息。
 */
public interface UserAccountService {

    Integer getAccountStatus(Long userId);
}
