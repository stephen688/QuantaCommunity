package com.quanta.demo0.platform.mq.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.platform.mq.entity.OutboxEvent;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 平台 Outbox 消息装配器：序列化已经由业务域构造好的消息，并追加通用事件元数据。
 *
 * 边界：不认识任何业务实体或业务消息字段；审核、Feed、通知等 payload 由各自域 producer 负责。
 */
@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;

    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;

    /**
     * 序列化消息并追加一条 Outbox 记录。
     */
    public String append(String eventId, String eventType, String aggregateType,
                         Long aggregateId, Object message) {
        return appendInternal(eventId, eventType, aggregateType, aggregateId, message, false);
    }

    /**
     * 序列化消息并以稳定 eventId 原子追加一条 Outbox 记录。
     */
    public String appendIfAbsent(String eventId, String eventType, String aggregateType,
                                 Long aggregateId, Object message) {
        return appendInternal(eventId, eventType, aggregateType, aggregateId, message, true);
    }

    private String appendInternal(String eventId, String eventType, String aggregateType,
                                  Long aggregateId, Object message, boolean insertIfAbsent) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Outbox 事件序列化失败", exception);
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new PayloadTooLargeException();
        }

        OutboxEvent event = OutboxEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .payload(payload)
                .nextRetryTime(LocalDateTime.now())
                .build();
        return insertIfAbsent
                ? outboxEventService.appendIfAbsent(event)
                : outboxEventService.append(event);
    }

    /** 平台层 payload 大小契约，供业务域恢复历史错误类型和消息。 */
    public static final class PayloadTooLargeException extends IllegalArgumentException {

        public PayloadTooLargeException() {
            super("Outbox payload 超过 32 KB");
        }
    }
}
