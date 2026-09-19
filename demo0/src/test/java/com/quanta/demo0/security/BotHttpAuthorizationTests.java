package com.quanta.demo0.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.SecurityConfiguration;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.QuantabotProperties;
import com.quanta.demo0.properties.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.Collections;
import java.util.Set;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * /bot/** 的 HTTP 二次边界：ROLE_BOT 之外还必须匹配配置的 bot userId。
 */
@SpringJUnitConfig(BotHttpAuthorizationTests.TestConfiguration.class)
@WebAppConfiguration
class BotHttpAuthorizationTests {

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
    void 普通用户即使携带BOT角色也不能访问Bot路径() throws Exception {
        when(tokenAuthenticationService.authenticate("user-bot-role-token"))
                .thenReturn(user(3L, Set.of("USER", "BOT")));

        mockMvc.perform(get("/bot/protected")
                        .header("authorization", "user-bot-role-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 配置的bot用户携带BOT角色可以访问Bot路径() throws Exception {
        when(tokenAuthenticationService.authenticate("bot-service-token"))
                .thenReturn(user(10000L, Set.of("USER", "BOT")));

        mockMvc.perform(get("/bot/protected")
                        .header("authorization", "bot-service-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("bot"));
    }

    private AuthenticatedUser user(Long userId, Set<String> roles) {
        return AuthenticatedUser.builder()
                .userId(userId)
                .roles(roles)
                .authorities(Collections.emptySet())
                .accountStatus(0)
                .verified(false)
                .admin(false)
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
        QuantabotProperties quantabotProperties() {
            return new QuantabotProperties();
        }

        @Bean
        TokenAuthenticationService tokenAuthenticationService() {
            return Mockito.mock(TokenAuthenticationService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        BotTestController botTestController() {
            return new BotTestController();
        }
    }

    @RestController
    static class BotTestController {

        @GetMapping("/bot/protected")
        String protectedBotEndpoint() {
            return "bot";
        }
    }
}
