package com.quanta.demo0.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目安全配置属性。
 * 当前用于统一管理HTTP和WebSocket允许的前端来源。
 */
@Component
@ConfigurationProperties(prefix = "quanta.security")
@Data
public class SecurityProperties {

    /**
     * 跨域配置。
     */
    private Cors cors = new Cors();

    /**
     * 是否开放接口文档（/doc.html、/v3/api-docs等）。
     *
     * 生产环境默认关闭，防止接口清单裸暴露；
     * 开发环境通过 API_DOCS_ENABLED=true 显式开启。
     */
    private Boolean apiDocsEnabled = Boolean.FALSE;

    /**
     * CORS跨域配置。
     */
    @Data
    public static class Cors {

        /**
         * 允许访问后端的前端来源。
         * 本地开发默认允许localhost和127.0.0.1的任意端口；
         * 生产环境应通过环境变量配置真实前端域名。
         */
        private List<String> allowedOriginPatterns = List.of(
                "http://localhost:*",
                "http://127.0.0.1:*"
        );
    }
}