package com.quanta.demo0.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.result.Result;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security未登录处理器。
 *
 * 当用户没有有效身份却访问受保护接口时，
 * 统一返回HTTP 401和JSON错误信息。
 */
@Component
public class SecurityAuthenticationEntryPoint
        implements AuthenticationEntryPoint {

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 处理未登录请求。
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );

        /*
         * 不向客户端暴露Token过期、用户不存在等详细原因，
         * 统一提示重新登录。
         */
        Result<Void> result = Result.error(
                401,
                "未登录或登录状态已失效"
        );

        objectMapper.writeValue(
                response.getWriter(),
                result
        );
    }
}