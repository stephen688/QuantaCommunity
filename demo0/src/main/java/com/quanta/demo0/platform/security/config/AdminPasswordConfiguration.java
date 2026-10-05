package com.quanta.demo0.platform.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 管理端密码装配：使用已有 Spring Security 的 BCrypt，不保存或输出明文密码。 */
@Configuration
public class AdminPasswordConfiguration {
    /** 12 轮 BCrypt 与离线凭据初始化工具保持一致。 */
    @Bean
    public PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
