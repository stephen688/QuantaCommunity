package com.quanta.demo0.platform.security;

import com.quanta.demo0.platform.security.constant.RolePermissionMapping;


import com.quanta.demo0.platform.security.handler.SecurityAccessDeniedHandler;
import com.quanta.demo0.platform.security.service.TokenAuthenticationService;
import com.quanta.demo0.platform.security.handler.SecurityAuthenticationEntryPoint;
import com.quanta.demo0.platform.security.filter.OptionalJwtAuthenticationFilter;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.security.config.SecurityConfiguration;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.content.controller.user.ContentController;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import com.quanta.demo0.platform.security.properties.SecurityProperties;
import com.quanta.demo0.content.service.ContentCommandService;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.feed.service.FeedQueryService;
import com.quanta.demo0.interaction.service.ContentInteractionService;
import com.quanta.demo0.interaction.service.ReportGovernanceService;
import com.quanta.demo0.platform.web.idempotency.service.SubmissionService;
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
import java.util.function.Supplier;

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
    private ContentCommandService contentCommandService;

    @Autowired
    private SubmissionService submissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(tokenAuthenticationService, contentCommandService);
        reset(submissionService);
        when(submissionService.execute(
                anyString(),
                any(),
                any(),
                eq(ContentVO.class),
                any()))
                .thenAnswer(invocation -> invocation.getArgument(4, Supplier.class).get());
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

        verify(contentCommandService, never()).publish(any());
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

        verify(contentCommandService, never()).publish(any());
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

        verify(contentCommandService).publish(any());
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
        ContentCommandService contentCommandService() {
            return mock(ContentCommandService.class);
        }

        @Bean
        ContentQueryService contentQueryService() {
            return mock(ContentQueryService.class);
        }

        @Bean
        FeedQueryService feedQueryService() {
            return mock(FeedQueryService.class);
        }

        @Bean
        ContentInteractionService contentInteractionService() {
            return mock(ContentInteractionService.class);
        }

        @Bean
        ReportGovernanceService reportGovernanceService() {
            return mock(ReportGovernanceService.class);
        }

        @Bean
        SubmissionService submissionService() {
            return mock(SubmissionService.class);
        }
    }
}
