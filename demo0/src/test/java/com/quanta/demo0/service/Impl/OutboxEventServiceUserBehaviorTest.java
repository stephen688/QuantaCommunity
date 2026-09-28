package com.quanta.demo0.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.platform.mq.entity.OutboxEvent;
import com.quanta.demo0.platform.mq.enums.OutboxEventStatus;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.OutboxEventMapper;
import com.quanta.demo0.mq.message.UserBehaviorMessage;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createUserBehaviorEvent 的事务性 Outbox 创建测试。
 */
class OutboxEventServiceUserBehaviorTest {

    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final OutboxEventServiceImpl service = new OutboxEventServiceImpl(
            outboxEventMapper,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            new OutboxDispatchProperties()
    );

    @Test
    void 创建事件_类型与聚合信息正确() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        String eventId = service.createUserBehaviorEvent(3L, 10L, "LIKE");

        assertNotNull(eventId);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();

        assertEquals(OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode(), event.getEventType());
        assertEquals("USER", event.getAggregateType());
        assertEquals(3L, event.getAggregateId());
        assertEquals(OutboxEventStatus.PENDING.getCode(), event.getStatus());
        assertTrue(event.getPayload().contains("\"userId\":3"));
        assertTrue(event.getPayload().contains("\"contentId\":10"));
        assertTrue(event.getPayload().contains("\"behaviorType\":\"LIKE\""));
    }

    @Test
    void 四种合法行为类型均可创建事件() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        // LIKE / COLLECT / COMMENT / VIEW 是 D2 定义的全部合法行为类型
        for (String behaviorType : new String[]{"LIKE", "COLLECT", "COMMENT", "VIEW"}) {
            assertNotNull(service.createUserBehaviorEvent(3L, 10L, behaviorType));
        }

        verify(outboxEventMapper, times(4)).insert(any(OutboxEvent.class));
    }

    @Test
    void 指定稳定eventId_原样透传到消息与Outbox() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        // 浏览对账任务的稳定 ID：同一 browse_history 行重复转发必须映射同一 eventId
        String stableEventId = "user.behavior.browse:123";
        String returnedEventId = service.createUserBehaviorEvent(3L, 10L, "VIEW", stableEventId);

        assertEquals(stableEventId, returnedEventId);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();

        assertEquals(stableEventId, event.getEventId());
        assertTrue(event.getPayload().contains("\"eventId\":\"" + stableEventId + "\""));
    }

    @Test
    void eventId为null_内部生成UUID并返回非空() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        // 赞/藏/评路径走三参版本（内部等价于 eventId=null）：必须生成非空 UUID 并写入消息与 Outbox
        String returnedEventId = service.createUserBehaviorEvent(3L, 10L, "LIKE", null);

        assertNotNull(returnedEventId);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();

        // 返回值、Outbox 行与 payload 三处的 eventId 必须一致，缺 eventId 的消息会被消费者死信
        assertEquals(returnedEventId, event.getEventId());
        assertTrue(event.getPayload().contains("\"eventId\":\"" + returnedEventId + "\""));
    }

    @Test
    void 插入失败抛异常_由调用方事务回滚() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service.createUserBehaviorEvent(3L, 10L, "LIKE"));
    }

    @Test
    void userId为null拒绝_不写入毒丸事件() {
        assertThrows(ContentFailedException.class,
                () -> service.createUserBehaviorEvent(null, 10L, "LIKE"));

        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void contentId为null拒绝_不写入毒丸事件() {
        assertThrows(ContentFailedException.class,
                () -> service.createUserBehaviorEvent(3L, null, "LIKE"));

        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void behaviorType为null拒绝_不写入毒丸事件() {
        assertThrows(ContentFailedException.class,
                () -> service.createUserBehaviorEvent(3L, 10L, null));

        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void 非法behaviorType拒绝_不写入毒丸事件() {
        // 不在 LIKE / COLLECT / COMMENT / VIEW 白名单内的值必须拒绝
        assertThrows(ContentFailedException.class,
                () -> service.createUserBehaviorEvent(3L, 10L, "VIEWED"));

        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void 消息模型jackson序列化往返兼容() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 23, 10, 30, 0);

        UserBehaviorMessage original = UserBehaviorMessage.builder()
                .userId(3L)
                .contentId(10L)
                .behaviorType("VIEW")
                .eventId("evt-user-behavior-1")
                .eventType(OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .retryCount(0)
                .build();

        String json = objectMapper.writeValueAsString(original);
        UserBehaviorMessage restored = objectMapper.readValue(json, UserBehaviorMessage.class);

        assertEquals(original.getUserId(), restored.getUserId());
        assertEquals(original.getContentId(), restored.getContentId());
        assertEquals(original.getBehaviorType(), restored.getBehaviorType());
        assertEquals(original.getEventId(), restored.getEventId());
        assertEquals(original.getEventType(), restored.getEventType());
        assertEquals(original.getOccurredAt(), restored.getOccurredAt());
        assertEquals(original.getRetryCount(), restored.getRetryCount());
    }
}
