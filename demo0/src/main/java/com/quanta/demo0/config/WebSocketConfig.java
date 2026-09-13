package com.quanta.demo0.config;

import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.properties.SecurityProperties;
import com.quanta.demo0.security.AuthenticatedUser;
import com.quanta.demo0.security.TokenAuthenticationException;
import com.quanta.demo0.security.TokenAuthenticationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 与HTTP入口共用的Token认证服务。
     */
    @Autowired
    private TokenAuthenticationService tokenAuthenticationService;


    /**
     * HTTP和WebSocket共用的安全配置。
     */
    @Autowired
    private SecurityProperties securityProperties;


    /**
     * 注册 STOMP 端点
     */
    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry
    ) {
        String[] allowedOriginPatterns =
                securityProperties
                        .getCors()
                        .getAllowedOriginPatterns()
                        .toArray(new String[0]);

        /*
         * WebSocket与HTTP使用同一份前端来源白名单，
         * 不再默认允许任意网站建立连接。
         */
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(
                        allowedOriginPatterns
                );
    }

    /**
     * 配置消息代理
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 启用简单消息代理，用于向客户端推送消息
        registry.enableSimpleBroker("/queue");
        // 客户端发送消息的前缀
        registry.setApplicationDestinationPrefixes("/app");
        // /user 前缀：服务端 convertAndSendToUser(userId, ...) 时，客户端订阅 /user/queue/notifications
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * 配置客户端入站通道拦截器。
     *
     * WebSocket握手完成后的STOMP CONNECT不是普通HTTP请求，
     * 因此不会经过HTTP拦截器，需要在消息通道中单独处理。
     *
     * 这里仅负责：
     * 1. 从STOMP Header读取Token；
     * 2. 调用统一认证服务；
     * 3. 把认证成功的userId绑定为Principal。
     */
    @Override
    public void configureClientInboundChannel(
            ChannelRegistration registration
    ) {
        registration.interceptors(new ChannelInterceptor() {

            @Override
            public Message<?> preSend(
                    Message<?> message,
                    MessageChannel channel
            ) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(
                                message,
                                StompHeaderAccessor.class
                        );

                /*
                 * 当前只处理STOMP CONNECT。
                 * SUBSCRIBE、DISCONNECT等消息暂时保持原有行为。
                 */
                if (accessor == null
                        || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                    return message;
                }

                String headerName = jwtProperties.getUserTokenName();
                String token = accessor.getFirstNativeHeader(headerName);

                if (token == null || token.isBlank()) {
                    log.warn("WebSocket连接失败：未携带Token");
                    return null;
                }

                try {
                    /*
                     * JWT、Redis登录态、用户存在性和封禁状态，
                     * 全部交给统一认证服务处理。
                     */
                    AuthenticatedUser authenticatedUser =
                            tokenAuthenticationService.authenticate(token);

                    Long userId = authenticatedUser.getUserId();

                    /*
                     * 将userId绑定为WebSocket Principal。
                     *
                     * 服务端调用convertAndSendToUser时，
                     * Spring会通过Principal.getName()找到对应连接。
                     */
                    accessor.setUser(new Principal() {
                        @Override
                        public String getName() {
                            return String.valueOf(userId);
                        }
                    });

                    log.info("WebSocket连接成功：userId={}", userId);
                    return message;
                } catch (TokenAuthenticationException exception) {
                    /*
                     * 只记录失败类型，不打印Token和敏感数据。
                     */
                    log.warn(
                            "WebSocket连接失败：reason={}",
                            exception.getReason()
                    );
                    return null;
                } catch (Exception exception) {
                    /*
                     * Redis、数据库等认证依赖发生异常时，
                     * WebSocket连接不能直接放行。
                     */
                    log.error(
                            "WebSocket认证发生系统异常：{}",
                            exception.getMessage()
                    );
                    return null;
                }
            }
        });
    }
}
