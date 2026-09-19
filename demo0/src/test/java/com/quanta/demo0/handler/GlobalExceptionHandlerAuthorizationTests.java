package com.quanta.demo0.handler;

import com.quanta.demo0.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方法级授权异常必须保留 403 语义，不能落入通用 500 处理。
 */
class GlobalExceptionHandlerAuthorizationTests {

    @Test
    void 方法级拒绝返回Http403与业务码403() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Void> result = handler.handleAccessDeniedException(
                new AccessDeniedException("Access Denied"),
                response
        );

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(result.getCode()).isEqualTo(403);
    }
}
