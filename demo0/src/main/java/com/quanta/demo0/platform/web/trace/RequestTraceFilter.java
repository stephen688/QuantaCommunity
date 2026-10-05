package com.quanta.demo0.platform.web.trace;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Enumeration;

/**
 * Web 请求关联过滤器。
 *
 * 职责：为每次 HTTP 请求确定 X-Request-Id、建立 MDC 作用域并在响应中回传编号。
 * 边界：不改变认证、授权、异常映射或业务响应；异步线程需通过 TraceContext 显式包装。
 */
@Slf4j
public class RequestTraceFilter implements Filter {

    /** 请求属性名，用于 ERROR/ASYNC redispatch 复用入口编号。 */
    public static final String TRACE_ID_ATTRIBUTE = "quanta.traceId";

    /**
     * 建立请求 MDC 作用域、转发请求并记录完成状态；下游异常原样继续抛出。
     */
    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain filterChain
    ) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            filterChain.doFilter(request, response);
            return;
        }

        String traceId = resolveFromSingleHeaderOrAttribute(httpRequest);
        httpRequest.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        httpResponse.setHeader(TraceContext.REQUEST_ID_HEADER, traceId);

        long startedAt = System.nanoTime();
        try (TraceContext.Scope ignored = TraceContext.open(traceId, null)) {
            boolean chainReturned = false;
            try {
                filterChain.doFilter(request, response);
                chainReturned = true;
            } finally {
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
                log.info(
                        "http_request_completed method={} path={} status={} durationMs={} chainReturned={}",
                        httpRequest.getMethod(),
                        httpRequest.getRequestURI(),
                        httpResponse.getStatus(),
                        durationMs,
                        chainReturned
                );
            }
        }
    }

    /**
     * 先复用 redispatch 上的合法属性；初始请求只接受一个合法 X-Request-Id，
     * 重复、超长或含控制字符的请求头均生成新编号且不回显原值。
     */
    private String resolveFromSingleHeaderOrAttribute(
            HttpServletRequest request
    ) {
        Object attribute = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String attributeValue
                && attributeValue.matches("[A-Za-z0-9_-]{1,64}")) {
            return attributeValue;
        }

        Enumeration<String> values = request.getHeaders(
                TraceContext.REQUEST_ID_HEADER
        );
        if (values == null || !values.hasMoreElements()) {
            return TraceContext.resolveHttp(null);
        }

        String candidate = values.nextElement();
        if (values.hasMoreElements()) {
            return TraceContext.resolveHttp(null);
        }
        return TraceContext.resolveHttp(candidate);
    }
}
