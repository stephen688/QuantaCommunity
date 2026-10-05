package com.quanta.demo0.platform.web.trace;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Web 关联过滤器装配配置。
 *
 * 职责：只注册一个位于 Spring Security 之前的 RequestTraceFilter。
 * 边界：不把过滤器重复加入 SecurityFilterChain，也不改变安全规则。
 */
@Configuration
public class TraceConfiguration {

    /**
     * 注册所有 HTTP 请求的关联过滤器。
     */
    @Bean
    public FilterRegistrationBean<RequestTraceFilter> requestTraceFilterRegistration() {
        FilterRegistrationBean<RequestTraceFilter> registration =
                new FilterRegistrationBean<>();
        registration.setFilter(new RequestTraceFilter());
        registration.setName("requestTraceFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(
                DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC
        );
        return registration;
    }
}
