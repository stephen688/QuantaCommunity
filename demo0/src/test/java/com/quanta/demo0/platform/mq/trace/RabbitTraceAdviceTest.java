package com.quanta.demo0.platform.mq.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.web.trace.TraceContext;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RabbitMQ 消费上下文 advice 的消息头提取和 MDC 生命周期测试。
 */
class RabbitTraceAdviceTest {

    private final RabbitTraceAdvice advice = new RabbitTraceAdvice(new ObjectMapper());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void restoresEnteringContextWhenListenerThrows() throws Throwable {
        MDC.put(TraceContext.TRACE_ID_KEY, "outer-trace");
        MDC.put(TraceContext.EVENT_ID_KEY, "outer-event");

        MessageProperties properties = new MessageProperties();
        properties.setHeader(TraceContext.REQUEST_ID_HEADER, "mq-trace-A");
        properties.setHeader(TraceContext.EVENT_ID_HEADER, "event-A");
        Message message = new Message("{}".getBytes(), properties);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});
        IllegalStateException failure = new IllegalStateException("listener failure");
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertEquals("mq-trace-A", MDC.get(TraceContext.TRACE_ID_KEY));
            assertEquals("event-A", MDC.get(TraceContext.EVENT_ID_KEY));
            throw failure;
        });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> advice.invoke(invocation)
        );

        assertSame(failure, thrown);
        assertEquals("outer-trace", MDC.get(TraceContext.TRACE_ID_KEY));
        assertEquals("outer-event", MDC.get(TraceContext.EVENT_ID_KEY));
    }

    @Test
    void derivesStableTraceFromEventIdWhenRequestHeaderIsMissing() throws Throwable {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(TraceContext.EVENT_ID_HEADER, "event-A");
        Message message = new Message(
                "{\"eventId\":\"event-A\"}".getBytes(),
                properties
        );
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertEquals(
                    TraceContext.resolveEvent(null, "event-A"),
                    MDC.get(TraceContext.TRACE_ID_KEY)
            );
            assertEquals("event-A", MDC.get(TraceContext.EVENT_ID_KEY));
            return "ok";
        });

        assertEquals("ok", advice.invoke(invocation));
        assertEquals(Map.of(), MDC.getCopyOfContextMap() == null
                ? Map.of()
                : MDC.getCopyOfContextMap());
    }
}
