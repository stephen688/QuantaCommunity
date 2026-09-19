package com.quanta.demo0.mq.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.enums.OutboxEventType;
import com.quanta.demo0.mq.message.BotMentionMessage;
import com.quanta.demo0.mq.message.OutboxRoute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * BOT_MENTION_REQUESTED 路由到 quantabot 拓扑（C-1 命名逐字对齐）。
 */
class OutboxRouteRegistryBotMentionTest {

    private final OutboxRouteRegistry registry =
            new OutboxRouteRegistry(new ObjectMapper());

    private OutboxEvent event(String payload) {
        return OutboxEvent.builder()
                .eventId("evt-1")
                .eventType(OutboxEventType.BOT_MENTION_REQUESTED.getCode())
                .aggregateType("COMMENT")
                .aggregateId(100L)
                .payload(payload)
                .build();
    }

    private String payload() {
        return """
                {
                  "eventId": "evt-1",
                  "eventType": "BOT_MENTION_REQUESTED",
                  "retryCount": 0,
                  "commentId": 100,
                  "postId": 10,
                  "commenterUserId": 3,
                  "commentContent": "@框框 你好",
                  "commentImages": [],
                  "mentionedBot": true,
                  "botTriggerKind": "mentioned"
                }
                """;
    }

    @Test
    void botMention事件路由到quantabot拓扑() {
        OutboxRoute route = registry.resolve(event(payload()));

        assertNotNull(route);
        assertEquals(RabbitMQConfig.BOT_MENTION_EXCHANGE, route.getExchange());
        assertEquals(RabbitMQConfig.BOT_MENTION_ROUTING_KEY, route.getRoutingKey());
        assertInstanceOf(BotMentionMessage.class, route.getMessage());
        assertEquals(100L, ((BotMentionMessage) route.getMessage()).getCommentId());
    }
}
