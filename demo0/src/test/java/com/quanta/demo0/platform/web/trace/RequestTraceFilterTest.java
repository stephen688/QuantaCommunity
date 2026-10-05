package com.quanta.demo0.platform.web.trace;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求关联过滤器的编号生成、MDC 清理和线程上下文恢复测试。
 */
class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsValidRequestIdAndCleansTraceContextAfterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private");
        request.addHeader(TraceContext.REQUEST_ID_HEADER, "request-A_01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertEquals("request-A_01", MDC.get(TraceContext.TRACE_ID_KEY));
            assertEquals("request-A_01", req.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE));
        });

        assertEquals("request-A_01", response.getHeader(TraceContext.REQUEST_ID_HEADER));
        assertNull(MDC.get(TraceContext.TRACE_ID_KEY));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "contains spaces", "a" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void replacesMissingOrInvalidRequestIdWithSafeGeneratedValue(String candidate)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private");
        if (candidate != null) {
            request.addHeader(TraceContext.REQUEST_ID_HEADER, candidate);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            String traceId = MDC.get(TraceContext.TRACE_ID_KEY);
            assertNotNull(traceId);
            assertTrue(traceId.matches("[0-9a-f]{32}"));
        });

        String responseTraceId = response.getHeader(TraceContext.REQUEST_ID_HEADER);
        assertNotNull(responseTraceId);
        assertTrue(responseTraceId.matches("[0-9a-f]{32}"));
        assertNull(MDC.get(TraceContext.TRACE_ID_KEY));
    }

    @Test
    void replacesDuplicateRequestIdsWithoutLoggingUntrustedValue() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private");
        request.addHeader(TraceContext.REQUEST_ID_HEADER, "request-A");
        request.addHeader(TraceContext.REQUEST_ID_HEADER, "request-B");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertTrue(MDC.get(TraceContext.TRACE_ID_KEY).matches("[0-9a-f]{32}"));
        });

        assertTrue(response.getHeader(TraceContext.REQUEST_ID_HEADER).matches("[0-9a-f]{32}"));
        assertNull(MDC.get(TraceContext.TRACE_ID_KEY));
    }

    @Test
    void restoresEnteringMdcWhenDownstreamThrows() {
        MDC.put(TraceContext.TRACE_ID_KEY, "outer-trace");
        MDC.put(TraceContext.EVENT_ID_KEY, "outer-event");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private");
        request.addHeader(TraceContext.REQUEST_ID_HEADER, "inner-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(
                request,
                response,
                (req, res) -> {
                    assertEquals("inner-trace", MDC.get(TraceContext.TRACE_ID_KEY));
                    throw new ServletException("downstream failure");
                }
        ));

        assertEquals("outer-trace", MDC.get(TraceContext.TRACE_ID_KEY));
        assertEquals("outer-event", MDC.get(TraceContext.EVENT_ID_KEY));
    }

    @Test
    void supplierCapturesAndRestoresAllMdcValues() {
        MDC.put(TraceContext.TRACE_ID_KEY, "captured-trace");
        MDC.put(TraceContext.EVENT_ID_KEY, "captured-event");
        MDC.put("request.user", "captured-user");
        Supplier<Map<String, String>> supplier = TraceContext.wrapSupplier(
                MDC::getCopyOfContextMap
        );

        MDC.put(TraceContext.TRACE_ID_KEY, "caller-trace");
        MDC.remove(TraceContext.EVENT_ID_KEY);
        MDC.put("request.user", "caller-user");

        Map<String, String> captured = supplier.get();

        assertEquals("captured-trace", captured.get(TraceContext.TRACE_ID_KEY));
        assertEquals("captured-event", captured.get(TraceContext.EVENT_ID_KEY));
        assertEquals("captured-user", captured.get("request.user"));
        assertEquals("caller-trace", MDC.get(TraceContext.TRACE_ID_KEY));
        assertNull(MDC.get(TraceContext.EVENT_ID_KEY));
        assertEquals("caller-user", MDC.get("request.user"));
    }
}
