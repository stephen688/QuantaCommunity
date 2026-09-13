package com.quanta.demo0.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.SecurityConfiguration;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.controller.user.ContentController;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.SecurityProperties;
import com.quanta.demo0.service.ContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 阶段3认证校友方法级授权验收测试。
 */
@SpringJUnitConfig(VerifiedUserMethodSecurityTests.TestConfiguration.class)
@WebAppConfiguration
class VerifiedUserMethodSecurityTests {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private TokenAuthenticationService tokenAuthenticationService;

    @Autowired
    private ContentService contentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(tokenAuthenticationService, contentService);
        mockMvc = webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void anonymousUserCannotPublishContent() throws Exception {
        mockMvc.perform(post("/content/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verify(contentService, never()).publish(any());
    }

    @Test
    void unverifiedUserCannotPublishContent() throws Exception {
        authenticateAs(
                "user-token",
                Set.of(RoleConstants.USER),
                false
        );

        mockMvc.perform(post("/content/publish")
                        .header("authorization", "user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(contentService, never()).publish(any());
    }

    @Test
    void verifiedUserCanPublishContent() throws Exception {
        authenticateAs(
                "verified-token",
                Set.of(
                        RoleConstants.USER,
                        RoleConstants.VERIFIED_USER
                ),
                true
        );

        mockMvc.perform(post("/content/publish")
                        .header("authorization", "verified-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(contentService).publish(any());
    }

    /**
     * 模拟统一Token认证服务返回指定角色的当前用户。
     */
    private void authenticateAs(
            String token,
            Set<String> roles,
            boolean verified
    ) {
        when(tokenAuthenticationService.authenticate(token))
                .thenReturn(AuthenticatedUser.builder()
                        .userId(7L)
                        .roles(roles)
                        .authorities(
                                RolePermissionMapping.permissionsFor(roles)
                        )
                        .accountStatus(0)
                        .verified(verified)
                        .admin(false)
                        .build());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfiguration.class,
            OptionalJwtAuthenticationFilter.class,
            SecurityAuthenticationEntryPoint.class,
            SecurityAccessDeniedHandler.class,
            ContentController.class
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
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TokenAuthenticationService tokenAuthenticationService() {
            return mock(TokenAuthenticationService.class);
        }

        @Bean
        ContentService contentService() {
            return mock(ContentService.class);
        }
    }
}
