package com.quanta.demo0.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.SecurityConfiguration;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.constant.RoleConstants;
import com.quanta.demo0.controller.admin.AdminContentController;
import com.quanta.demo0.controller.admin.AdminEventController;
import com.quanta.demo0.controller.admin.AdminUserController;
import com.quanta.demo0.controller.admin.IdentityExamController;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.SecurityProperties;
import com.quanta.demo0.service.AdminContentService;
import com.quanta.demo0.service.AdminEventService;
import com.quanta.demo0.service.AdminUserService;
import com.quanta.demo0.service.IdentityExamService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 阶段3管理端方法级授权验收测试。
 */
@SpringJUnitConfig(AdminMethodSecurityTests.TestConfiguration.class)
@WebAppConfiguration
class AdminMethodSecurityTests {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private TokenAuthenticationService tokenAuthenticationService;

    @Autowired
    private AdminContentService adminContentService;

    @Autowired
    private AdminEventService adminEventService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(
                tokenAuthenticationService,
                adminContentService,
                adminEventService
        );
        BaseContext.removeCurrentId();
        mockMvc = webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void contentAuditorCanReadAndAuditButCannotDelete() throws Exception {
        authenticateAs(
                "content-token",
                Set.of(RoleConstants.USER, RoleConstants.CONTENT_AUDITOR)
        );

        mockMvc.perform(get("/admin/content/page")
                        .header("authorization", "content-token"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/content/audit")
                        .header("authorization", "content-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/admin/content/1")
                        .header("authorization", "content-token"))
                .andExpect(status().isForbidden());

        verify(adminContentService, never()).deleteContent(1L);
    }

    @Test
    void operationsAdminCanReadAndBanUsersAndReviewIdentity() throws Exception {
        authenticateAs(
                "operations-token",
                Set.of(RoleConstants.USER, RoleConstants.OPERATIONS_ADMIN)
        );

        mockMvc.perform(get("/admin/user/page")
                        .header("authorization", "operations-token"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/user/ban/8")
                        .header("authorization", "operations-token"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/identityExam/page")
                        .header("authorization", "operations-token"))
                .andExpect(status().isOk());
    }

    @Test
    void operationsAdminCannotAuditContent() throws Exception {
        authenticateAs(
                "operations-token",
                Set.of(RoleConstants.USER, RoleConstants.OPERATIONS_ADMIN)
        );

        mockMvc.perform(post("/admin/content/audit")
                        .header("authorization", "operations-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(adminContentService, never()).audit(any());
    }

    @Test
    void eventReadPermissionCannotReplayEvent() throws Exception {
        authenticateAs(
                "operations-token",
                Set.of(RoleConstants.USER, RoleConstants.OPERATIONS_ADMIN)
        );

        mockMvc.perform(get("/admin/events/overview")
                        .header("authorization", "operations-token"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/events/outbox/event-1/replay")
                        .header("authorization", "operations-token"))
                .andExpect(status().isForbidden());

        verify(adminEventService, never()).replayOutbox("event-1");
    }

    @Test
    void superAdminCanDeleteContentAndReplayEvent() throws Exception {
        authenticateAs(
                "admin-token",
                Set.of(RoleConstants.USER, RoleConstants.SUPER_ADMIN)
        );

        mockMvc.perform(delete("/admin/content/1")
                        .header("authorization", "admin-token"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/events/outbox/event-1/replay")
                        .header("authorization", "admin-token"))
                .andExpect(status().isOk());

        verify(adminContentService).deleteContent(1L);
        verify(adminEventService).replayOutbox("event-1");
    }

    private void authenticateAs(
            String token,
            Set<String> roles
    ) {
        when(tokenAuthenticationService.authenticate(token))
                .thenReturn(AuthenticatedUser.builder()
                        .userId(7L)
                        .roles(roles)
                        .authorities(
                                RolePermissionMapping.permissionsFor(roles)
                        )
                        .accountStatus(0)
                        .verified(false)
                        .admin(roles.contains(RoleConstants.SUPER_ADMIN))
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
            AdminContentController.class,
            AdminUserController.class,
            IdentityExamController.class,
            AdminEventController.class
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
        AdminContentService adminContentService() {
            return mock(AdminContentService.class);
        }

        @Bean
        AdminUserService adminUserService() {
            return mock(AdminUserService.class);
        }

        @Bean
        IdentityExamService identityExamService() {
            return mock(IdentityExamService.class);
        }

        @Bean
        AdminEventService adminEventService() {
            return mock(AdminEventService.class);
        }
    }
}
