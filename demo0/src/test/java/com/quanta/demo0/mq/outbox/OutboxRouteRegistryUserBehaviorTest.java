package com.quanta.demo0.mq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.enums.OutboxEventType;
import com.quanta.demo0.mq.message.OutboxRoute;
import com.quanta.demo0.mq.message.UserBehaviorMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * USER_BEHAVIOR_REQUESTED 路由到 user.behavior 拓扑（画像更新信号）。
 */
class OutboxRouteRegistryUserBehaviorTest {

    private final OutboxRouteRegistry registry =
            new OutboxRouteRegistry(new ObjectMapper().registerModule(new JavaTimeModule()));

    private OutboxEvent event(String payload) {
        return OutboxEvent.builder()
                .eventId("evt-1")
                .eventType(OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode())
                .aggregateType("USER")
                .aggregateId(3L)
                .payload(payload)
                .build();
    }

    private String payload() {
        return """
                {
                  "eventId": "evt-1",
                  "eventType": "USER_BEHAVIOR_REQUESTED",
                  "retryCount": 0,
                  "userId": 3,
                  "contentId": 10,
                  "behaviorType": "LIKE",
                  "occurredAt": [2026, 9, 23, 10, 30, 0]
                }
                """;
    }

    @Test
    void userBehavior事件路由到画像拓扑() {
        OutboxRoute route = registry.resolve(event(payload()));

        assertNotNull(route);
        assertEquals(RabbitMQConfig.USER_BEHAVIOR_EXCHANGE, route.getExchange());
        assertEquals(RabbitMQConfig.USER_BEHAVIOR_ROUTING_KEY, route.getRoutingKey());
        assertInstanceOf(UserBehaviorMessage.class, route.getMessage());
        assertEquals(3L, ((UserBehaviorMessage) route.getMessage()).getUserId());
        assertEquals(10L, ((UserBehaviorMessage) route.getMessage()).getContentId());
        assertEquals("LIKE", ((UserBehaviorMessage) route.getMessage()).getBehaviorType());
    }
}
