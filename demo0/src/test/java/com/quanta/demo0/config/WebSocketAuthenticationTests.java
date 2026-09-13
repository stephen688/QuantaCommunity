package com.quanta.demo0.config;

import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.SecurityProperties;
import com.quanta.demo0.security.AuthenticatedUser;
import com.quanta.demo0.security.TokenAuthenticationException;
import com.quanta.demo0.security.TokenAuthenticationFailureReason;
import com.quanta.demo0.security.TokenAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 阶段1 WebSocket统一认证验收测试。
 */
class WebSocketAuthenticationTests {

    private TokenAuthenticationService tokenAuthenticationService;
    private ChannelInterceptor channelInterceptor;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setUserTokenName("authorization");

        tokenAuthenticationService = mock(TokenAuthenticationService.class);

        WebSocketConfig webSocketConfig = new WebSocketConfig();
        ReflectionTestUtils.setField(
                webSocketConfig,
                "jwtProperties",
                jwtProperties
        );
        ReflectionTestUtils.setField(
                webSocketConfig,
                "tokenAuthenticationService",
                tokenAuthenticationService
        );
        ReflectionTestUtils.setField(
                webSocketConfig,
                "securityProperties",
                new SecurityProperties()
        );

        ChannelRegistration registration = mock(ChannelRegistration.class);
        webSocketConfig.configureClientInboundChannel(registration);

        ArgumentCaptor<ChannelInterceptor[]> captor =
                ArgumentCaptor.forClass(ChannelInterceptor[].class);
        verify(registration).interceptors(captor.capture());
        channelInterceptor = captor.getValue()[0];
    }

    @Test
    void nonConnectMessagePassesWithoutAuthentication() {
        Message<byte[]> message = stompMessage(
                StompCommand.SUBSCRIBE,
                null
        );

        Message<?> result = channelInterceptor.preSend(
                message,
                mock(MessageChannel.class)
        );

        assertSame(message, result);
        verifyNoInteractions(tokenAuthenticationService);
    }

    @Test
    void connectWithoutTokenIsRejected() {
        Message<byte[]> message = stompMessage(
                StompCommand.CONNECT,
                null
        );

        assertNull(channelInterceptor.preSend(
                message,
                mock(MessageChannel.class)
        ));
        verifyNoInteractions(tokenAuthenticationService);
    }

    @Test
    void validConnectBindsUserIdPrincipal() {
        when(tokenAuthenticationService.authenticate("valid-token"))
                .thenReturn(AuthenticatedUser.builder()
                        .userId(7L)
                        .roles(Set.of("USER"))
                        .authorities(Collections.emptySet())
                        .build());

        Message<byte[]> message = stompMessage(
                StompCommand.CONNECT,
                "valid-token"
        );

        Message<?> result = channelInterceptor.preSend(
                message,
                mock(MessageChannel.class)
        );

        assertSame(message, result);
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        result,
                        StompHeaderAccessor.class
                );
        assertNotNull(accessor);
        assertNotNull(accessor.getUser());
        assertEquals("7", accessor.getUser().getName());
    }

    @Test
    void invalidConnectTokenIsRejected() {
        when(tokenAuthenticationService.authenticate("invalid-token"))
                .thenThrow(new TokenAuthenticationException(
                        TokenAuthenticationFailureReason.TOKEN_INVALID,
                        "登录凭证无效"
                ));

        Message<byte[]> message = stompMessage(
                StompCommand.CONNECT,
                "invalid-token"
        );

        assertNull(channelInterceptor.preSend(
                message,
                mock(MessageChannel.class)
        ));
    }

    @Test
    void authenticationDependencyFailureIsRejected() {
        when(tokenAuthenticationService.authenticate("valid-token"))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        Message<byte[]> message = stompMessage(
                StompCommand.CONNECT,
                "valid-token"
        );

        assertNull(channelInterceptor.preSend(
                message,
                mock(MessageChannel.class)
        ));
    }

    private Message<byte[]> stompMessage(
            StompCommand command,
            String token
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(command);
        if (token != null) {
            accessor.setNativeHeader("authorization", token);
        }
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }
}
