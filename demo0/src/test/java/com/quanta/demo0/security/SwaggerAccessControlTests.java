package com.quanta.demo0.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.SecurityConfiguration;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 阶段7：Swagger 按环境开关开放的安全测试。
 *
 * 生产语义（apiDocsEnabled=false，默认）：
 * 匿名访问文档路径必须被认证层拦截，返回 401。
 *
 * 开发语义（apiDocsEnabled=true）：
 * 文档路径放行，可以正常访问。
 */
class SwaggerAccessControlTests {

    @Nested
    @SpringJUnitConfig(ClosedConfiguration.class)
    @WebAppConfiguration
    class WhenApiDocsDisabled {

        @Autowired
        private WebApplicationContext applicationContext;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            BaseContext.removeCurrentId();
            mockMvc = webAppContextSetup(applicationContext)
                    .apply(springSecurity())
                    .build();
        }

        @Test
        void anonymousApiDocsRequestReturns401() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @SpringJUnitConfig(OpenConfiguration.class)
    @WebAppConfiguration
    class WhenApiDocsEnabled {

        @Autowired
        private WebApplicationContext applicationContext;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
            BaseContext.removeCurrentId();
            mockMvc = webAppContextSetup(applicationContext)
                    .apply(springSecurity())
                    .build();
        }

        @Test
        void anonymousApiDocsRequestPasses() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("api-docs"));
        }
    }

    /**
     * 生产语义配置：apiDocsEnabled 使用默认值 false。
     */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfiguration.class,
            OptionalJwtAuthenticationFilter.class,
            SecurityAuthenticationEntryPoint.class,
            SecurityAccessDeniedHandler.class
    })
    static class ClosedConfiguration {

        @Bean
        JwtProperties jwtProperties() {
            JwtProperties properties = new JwtProperties();
            properties.setUserTokenName("authorization");
            return properties;
        }

        @Bean
        SecurityProperties securityProperties() {
            return new SecurityProperties();
        }

        @Bean
        TokenAuthenticationService tokenAuthenticationService() {
            return mock(TokenAuthenticationService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        DocsTestController docsTestController() {
            return new DocsTestController();
        }
    }

    /**
     * 开发语义配置：apiDocsEnabled 显式开启。
     */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfiguration.class,
            OptionalJwtAuthenticationFilter.class,
            SecurityAuthenticationEntryPoint.class,
            SecurityAccessDeniedHandler.class
    })
    static class OpenConfiguration {

        @Bean
        JwtProperties jwtProperties() {
            JwtProperties properties = new JwtProperties();
            properties.setUserTokenName("authorization");
            return properties;
        }

        @Bean
        SecurityProperties securityProperties() {
            SecurityProperties properties = new SecurityProperties();
            properties.setApiDocsEnabled(Boolean.TRUE);
            return properties;
        }

        @Bean
        TokenAuthenticationService tokenAuthenticationService() {
            return mock(TokenAuthenticationService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        DocsTestController docsTestController() {
            return new DocsTestController();
        }
    }

    @RestController
    static class DocsTestController {

        @GetMapping("/v3/api-docs")
        String docs() {
            return "api-docs";
        }
    }
}
