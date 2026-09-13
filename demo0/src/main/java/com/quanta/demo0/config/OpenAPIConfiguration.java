package com.quanta.demo0.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class OpenAPIConfiguration {
    /**
     * OpenAPI配置类
     */

    @Bean
    public OpenAPI customOpenAPI() {
        log.info("开始设置OpenAPI接口文档相关配置....");

        // 定义API密钥认证方案
        SecurityScheme apiKey = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .name("authorization")  // 与JwtProperties中的userTokenName一致
                .in(SecurityScheme.In.HEADER)
                .description("JWT认证令牌");

        // 添加安全要求
        SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList("BearerAuth");

        return new OpenAPI()
                .info(new Info()
                        .title("Quanta校友联络平台接口文档")
                        .version("1.0")
                        .description("Quanta校友联络平台接口文档"))
                .addSecurityItem(securityRequirement)
                .components(
                        new io.swagger.v3.oas.models.Components()
                                .addSecuritySchemes("BearerAuth", apiKey)
                );
    }
}