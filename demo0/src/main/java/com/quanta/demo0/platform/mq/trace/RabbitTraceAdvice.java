package com.quanta.demo0.platform.mq.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.web.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RabbitMQ 消费日志关联 advice。
 *
 * 职责：从监听容器传入的原始 Message 提取关联头，建立当前消费的 MDC
 * 作用域，并在监听调用结束后恢复线程原值。边界：不改变消费者的消息转换、
 * ACK、重试和死信决策；无法解析的头只使用稳定事件兜底，不吞掉业务异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitTraceAdvice implements MethodInterceptor {

    private static final int MAX_MESSAGE_BYTES = 32 * 1024;

    private final ObjectMapper objectMapper;

    /**
     * 包住一次监听调用，保证线程池复用时不会残留上一个消息的关联编号。
     *
     * @param invocation Spring AOP 监听调用
     * @return 原监听器返回值
     * @throws Throwable 原监听器抛出的异常，保持原有消费容器规则
     */
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = findMessage(invocation);
        String eventId = resolveEventId(message);
        String traceId = TraceContext.resolveEvent(
                headerValue(message, TraceContext.REQUEST_ID_HEADER),
                eventId
        );
        long startedNanos = System.nanoTime();

        try (TraceContext.Scope ignored = TraceContext.open(traceId, eventId)) {
            log.info("mq_listener_started listener={}, eventId={}",
                    listenerName(invocation), eventId);
            try {
                Object result = invocation.proceed();
                log.info("mq_listener_returned listener={}, eventId={}, durationMs={}",
                        listenerName(invocation), eventId, elapsedMillis(startedNanos));
                return result;
            } catch (Throwable exception) {
                log.error("mq_listener_threw listener={}, eventId={}, durationMs={}",
                        listenerName(invocation), eventId, elapsedMillis(startedNanos), exception);
                throw exception;
            }
        }
    }

    /**
     * 从 advice 调用参数中寻找 Spring AMQP 的原始消息，避免依赖转换后的业务 DTO。
     */
    private Message findMessage(MethodInvocation invocation) {
        Object[] arguments = invocation.getArguments();
        if (arguments == null) {
            return null;
        }
        for (Object argument : arguments) {
            if (argument instanceof Message message) {
                return message;
            }
        }
        return null;
    }

    /**
     * 优先读取 X-Event-Id；旧消息无该头时只读取小消息 JSON 的根 eventId 字段。
     */
    private String resolveEventId(Message message) {
        String headerEventId = headerValue(message, TraceContext.EVENT_ID_HEADER);
        if (TraceContext.isValidEventId(headerEventId)) {
            return headerEventId;
        }
        if (message == null || message.getBody() == null
                || message.getBody().length > MAX_MESSAGE_BYTES) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            if (root != null && root.isObject()
                    && root.path("eventId").isTextual()) {
                String bodyEventId = root.path("eventId").asText(null);
                if (TraceContext.isValidEventId(bodyEventId)) {
                    return bodyEventId;
                }
            }
        } catch (Exception ignored) {
            // 消息转换器负责决定毒丸/拒绝策略；关联解析失败不能改变它。
        }
        return null;
    }

    /**
     * 只接受字符串或短 ASCII 字节数组头，避免把任意对象内容写入 MDC。
     */
    private String headerValue(Message message, String name) {
        if (message == null || message.getMessageProperties() == null) {
            return null;
        }
        Object value = message.getMessageProperties().getHeaders().get(name);
        if (value instanceof byte[] bytes) {
            if (bytes.length > 128) {
                return null;
            }
            return new String(bytes, StandardCharsets.US_ASCII);
        }
        if (value instanceof String text && text.length() <= 128) {
            return text;
        }
        return null;
    }

    private String listenerName(MethodInvocation invocation) {
        if (invocation.getMethod() == null) {
            return "unknown";
        }
        return invocation.getMethod().getDeclaringClass().getSimpleName()
                + "#" + invocation.getMethod().getName();
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
