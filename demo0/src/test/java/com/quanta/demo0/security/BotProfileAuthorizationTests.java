package com.quanta.demo0.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.feed.controller.bot.BotProfileController;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.platform.web.handler.GlobalExceptionHandler;
import com.quanta.demo0.service.ExplicitPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** 实际画像 Controller 的 URL/方法权限及DTO边界；认证依赖替身只代表外部认证结果，不替换鉴权过滤器。 */
@SpringJUnitConfig({BotHttpAuthorizationTests.TestConfiguration.class,BotProfileAuthorizationTests.ProfileBeans.class})
@WebAppConfiguration
class BotProfileAuthorizationTests {
    @Autowired WebApplicationContext context;
    @Autowired TokenAuthenticationService auth;
    @Autowired ExplicitPreferenceService preferences;
    private MockMvc mvc;
    private static final String PAYLOAD = """
            {"eventId":"b09ac1bd-f5f3-43e0-a93b-2f2606daf0a8","userId":123,
             "memoryId":"6110e1d664174ad8a67a73cb49b28edc","personaVersion":"v1","revision":100,
             "operation":"UPSERT","topics":["basketball"],"valence":"positive"}
            """;
    @BeforeEach void prepare() {
        reset(auth,preferences); BaseContext.removeCurrentId();
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void noTokenCannotReadOrWriteProfile() throws Exception {
        mvc.perform(get("/bot/profile/topics")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/bot/profile/events").contentType("application/json").content(PAYLOAD))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(preferences);
    }
    @Test void forgedAndBannedTokensCannotWriteProfile() throws Exception {
        for (var reason : Set.of(TokenAuthenticationFailureReason.TOKEN_INVALID,TokenAuthenticationFailureReason.USER_BANNED)) {
            doThrow(new TokenAuthenticationException(reason,"凭据无效或账户封禁")).when(auth).authenticate("invalid");
            mvc.perform(post("/bot/profile/events").header("Authorization","Bearer invalid")
                    .contentType("application/json").content(PAYLOAD)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(preferences);
    }
    @Test void ordinaryUserEvenWithBotRoleCannotWriteProfile() throws Exception {
        when(auth.authenticate("ordinary")).thenReturn(principal(3L,Set.of("USER","BOT")));
        mvc.perform(post("/bot/profile/events").header("Authorization","Bearer ordinary")
                .contentType("application/json").content(PAYLOAD)).andExpect(status().isForbidden());
        verifyNoInteractions(preferences);
    }
    @Test void configuredBotCanReadVocabularyAndSubmitValidatedEvent() throws Exception {
        when(auth.authenticate("bot")).thenReturn(principal(10000L,Set.of("USER","BOT")));
        when(preferences.accept(any())).thenReturn(true);
        mvc.perform(get("/bot/profile/topics").header("Authorization","Bearer bot"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(26));
        mvc.perform(post("/bot/profile/events").header("Authorization","Bearer bot")
                .contentType("application/json").content(PAYLOAD)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
    }
    @Test void botCannotSubmitMoreThanThreeTopics() throws Exception {
        when(auth.authenticate("bot")).thenReturn(principal(10000L,Set.of("USER","BOT")));
        String oversized = PAYLOAD.replace("[\"basketball\"]","[\"basketball\",\"football\",\"running\",\"swimming\"]");
        mvc.perform(post("/bot/profile/events").header("Authorization","Bearer bot")
                .contentType("application/json").content(oversized)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(preferences);
    }
    private AuthenticatedUser principal(Long userId,Set<String> roles) {
        return AuthenticatedUser.builder().userId(userId).roles(roles).authorities(Set.of()).accountStatus(0)
                .verified(false).admin(false).build();
    }
    @Configuration @Import({BotProfileController.class,GlobalExceptionHandler.class})
    static class ProfileBeans {
        @Bean ExplicitPreferenceService preferences() { return mock(ExplicitPreferenceService.class); }
    }
}
