package com.quanta.demo0.security;

import com.quanta.demo0.platform.security.model.AuthenticatedUser;
import com.quanta.demo0.platform.security.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bearer 前缀剥离：主服务调用使用 Bearer，旧客户端仍可发送裸 token。
 */
@ExtendWith(MockitoExtension.class)
class OptionalJwtAuthenticationFilterBearerTest {

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private TokenAuthenticationService tokenAuthenticationService;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private OptionalJwtAuthenticationFilter filter;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/bot/comment/chain");
        request.addHeader("authorization", headerValue);
        return request;
    }

    private void stubAuthentication() {
        when(tokenAuthenticationService.authenticate(anyString()))
                .thenReturn(AuthenticatedUser.builder()
                        .userId(1L)
                        .roles(java.util.Set.of())
                        .build());
    }

    @Test
    void bearer前缀被剥离后传给认证服务() throws Exception {
        when(jwtProperties.getUserTokenName()).thenReturn("authorization");
        stubAuthentication();

        filter.doFilter(
                request("Bearer abc.def.ghi"),
                new MockHttpServletResponse(),
                filterChain
        );

        verify(tokenAuthenticationService).authenticate(eq("abc.def.ghi"));
    }

    @Test
    void 裸token原样传给认证服务() throws Exception {
        when(jwtProperties.getUserTokenName()).thenReturn("authorization");
        stubAuthentication();

        filter.doFilter(
                request("abc.def.ghi"),
                new MockHttpServletResponse(),
                filterChain
        );

        verify(tokenAuthenticationService).authenticate(eq("abc.def.ghi"));
    }
}
