package com.quanta.demo0.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.security.config.SecurityConfiguration;
import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.platform.security.constant.RolePermissionMapping;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.platform.security.controller.admin.AdminRoleController;
import com.quanta.demo0.platform.security.filter.OptionalJwtAuthenticationFilter;
import com.quanta.demo0.platform.security.handler.SecurityAccessDeniedHandler;
import com.quanta.demo0.platform.security.handler.SecurityAuthenticationEntryPoint;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import com.quanta.demo0.platform.security.properties.SecurityProperties;
import com.quanta.demo0.platform.security.service.AdminRoleService;
import com.quanta.demo0.platform.security.service.TokenAuthenticationService;
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
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * 管理端角色控制器安全契约测试。
 *
 * <p>锁定角色读取接口的认证、权限和成功响应边界。</p>
 */
@SpringJUnitConfig(AdminRoleControllerTests.TestConfiguration.class)
@WebAppConfiguration
class AdminRoleControllerTests {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private TokenAuthenticationService tokenAuthenticationService;

    @Autowired
    private AdminRoleService adminRoleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(tokenAuthenticationService, adminRoleService);
        mockMvc = webAppContextSetup(applicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void userReadPermissionReturnsCurrentRoles() throws Exception {
        authenticateAs(
                "user-read-token",
                Set.of(RoleConstants.USER, RoleConstants.OPERATIONS_ADMIN)
        );
        when(adminRoleService.getUserRoles(42L))
                .thenReturn(List.of(RoleConstants.CONTENT_AUDITOR));

        mockMvc.perform(get("/admin/roles/user/42")
                        .header("authorization", "user-read-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]")
                        .value(RoleConstants.CONTENT_AUDITOR));
    }

    @Test
    void ordinaryUserCannotReadRoles() throws Exception {
        authenticateAs(
                "ordinary-user-token",
                Set.of(RoleConstants.USER)
        );

        mockMvc.perform(get("/admin/roles/user/42")
                        .header("authorization", "ordinary-user-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserMustAuthenticateBeforeReadingRoles() throws Exception {
        mockMvc.perform(get("/admin/roles/user/42"))
                .andExpect(status().isUnauthorized());
    }

    private void authenticateAs(String token, Set<String> roles) {
        when(tokenAuthenticationService.authenticate(token))
                .thenReturn(AuthenticatedUser.builder()
                        .userId(7L)
                        .roles(roles)
                        .authorities(RolePermissionMapping.permissionsFor(roles))
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
            AdminRoleController.class
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
        AdminRoleService adminRoleService() {
            return mock(AdminRoleService.class);
        }
    }
}
