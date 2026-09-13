package com.quanta.demo0.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.SecurityConfiguration;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 阶段2 Spring Security过滤器链验收测试。
 */
@SpringJUnitConfig(SecurityFilterChainTests.TestConfiguration.class)
@WebAppConfiguration
class SecurityFilterChainTests {

    private static final Long USER_ID = 7L;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private TokenAuthenticationService tokenAuthenticationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(tokenAuthenticationService);
        BaseContext.removeCurrentId();
        mockMvc = webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void optionalPathWithoutTokenRemainsAnonymous() throws Exception {
        mockMvc.perform(get("/content/recommend"))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));

        verifyNoInteractions(tokenAuthenticationService);
    }

    @Test
    void protectedPathWithoutTokenReturnsJson401() throws Exception {
        mockMvc.perform(get("/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("未登录或登录状态已失效"));
    }

    @Test
    void invalidTokenOnOptionalPathFallsBackToAnonymous() throws Exception {
        when(tokenAuthenticationService.authenticate("invalid-token"))
                .thenThrow(new TokenAuthenticationException(
                        TokenAuthenticationFailureReason.TOKEN_INVALID,
                        "登录凭证无效"
                ));

        mockMvc.perform(get("/content/recommend")
                        .header("authorization", "invalid-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("anonymous"));
    }

    @Test
    void invalidTokenOnProtectedPathReturnsJson401() throws Exception {
        when(tokenAuthenticationService.authenticate("invalid-token"))
                .thenThrow(new TokenAuthenticationException(
                        TokenAuthenticationFailureReason.TOKEN_INVALID,
                        "登录凭证无效"
                ));

        mockMvc.perform(get("/private")
                        .header("authorization", "invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void validTokenBuildsSecurityContextAndCleansBaseContext() throws Exception {
        when(tokenAuthenticationService.authenticate("user-token"))
                .thenReturn(user(Set.of("USER")));

        mockMvc.perform(get("/private")
                        .header("authorization", "user-token"))
                .andExpect(status().isOk())
                .andExpect(content().string(USER_ID.toString()));

        assertNull(BaseContext.getCurrentId());
    }

    @Test
    void normalUserAccessingAdminPathReturnsJson403() throws Exception {
        when(tokenAuthenticationService.authenticate("user-token"))
                .thenReturn(user(Set.of("USER")));

        mockMvc.perform(get("/admin/check")
                        .header("authorization", "user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("没有权限执行该操作"));
    }

    @Test
    void superAdminCanAccessAdminPath() throws Exception {
        when(tokenAuthenticationService.authenticate("admin-token"))
                .thenReturn(user(Set.of("USER", "SUPER_ADMIN")));

        mockMvc.perform(get("/admin/check")
                        .header("authorization", "admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("admin"));
    }

    @Test
    void configuredOriginCanSendCorsPreflight() throws Exception {
        mockMvc.perform(options("/private")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:5173"
                ));
    }

    @Test
    void unknownOriginIsRejected() throws Exception {
        mockMvc.perform(options("/private")
                        .header("Origin", "https://unknown.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private AuthenticatedUser user(Set<String> roles) {
        return AuthenticatedUser.builder()
                .userId(USER_ID)
                .roles(roles)
                .authorities(Collections.emptySet())
                .accountStatus(0)
                .verified(false)
                .admin(roles.contains("SUPER_ADMIN"))
                .build();
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfiguration.class,
            OptionalJwtAuthenticationFilter.class,
            SecurityAuthenticationEntryPoint.class,
            SecurityAccessDeniedHandler.class
    })
    static class TestConfiguration {

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
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/content/recommend")
        String recommend() {
            Long userId = BaseContext.getCurrentId();
            return userId == null ? "anonymous" : userId.toString();
        }

        @GetMapping("/private")
        String privateEndpoint() {
            return String.valueOf(BaseContext.getCurrentId());
        }

        @GetMapping("/admin/check")
        String adminEndpoint() {
            return "admin";
        }
    }
}
