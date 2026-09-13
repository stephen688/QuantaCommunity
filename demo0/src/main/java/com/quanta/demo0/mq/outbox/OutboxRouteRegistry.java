package com.quanta.demo0.mq.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.enums.OutboxEventType;

import com.quanta.demo0.mq.message.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Outbox 事件路由注册表。
 */
@Component
@RequiredArgsConstructor
public class OutboxRouteRegistry {

    private final ObjectMapper objectMapper;

    /**
     * 根据事件类型决定：
     * 1. 发送到哪个交换机；
     * 2. 使用哪个路由键；
     * 3. payload 反序列化成什么消息。
     */
    public OutboxRoute resolve(OutboxEvent event) {
       // 审核事件
        if (OutboxEventType.MODERATION_REQUESTED
                .getCode()
                .equals(event.getEventType())) {

            ModerationTaskMessage message =
                    deserializeModerationMessage(
                            event.getPayload()
                    );

            return OutboxRoute.builder()
                    .exchange(
                            RabbitMQConfig.MODERATION_EXCHANGE
                    )
                    .routingKey(
                            RabbitMQConfig.MODERATION_ROUTING_KEY
                    )
                    .message(message)
                    .build();
        }
        /**
         * 通知事件。
         */
        if (OutboxEventType.NOTIFICATION_REQUESTED
                .getCode()
                .equals(event.getEventType())) {

            NotificationEventMessage message =
                    deserializeNotificationMessage(
                            event.getPayload()
                    );

            return OutboxRoute.builder()
                    .exchange(
                            RabbitMQConfig.NOTIFICATION_EXCHANGE
                    )
                    .routingKey(
                            RabbitMQConfig.NOTIFICATION_ROUTING_KEY
                    )
                    .message(message)
                    .build();
        }
        /**
         * Feed 校准事件。
         */
        if (OutboxEventType.FEED_UPSERT_REQUESTED.getCode().equals(event.getEventType())) {
            FeedPushMessage message = deserializePayload(event.getPayload(), FeedPushMessage.class);

            return OutboxRoute.builder()
                    .exchange(RabbitMQConfig.FEED_PUSH_EXCHANGE)
                    .routingKey(RabbitMQConfig.FEED_PUSH_ROUTING_KEY)
                    .message(message)
                    .build();
        }
        /**
         * Feed 校准事件。
         */

        if (OutboxEventType.FEED_DELETE_REQUESTED.getCode().equals(event.getEventType())) {
            FeedDeleteMessage message = deserializePayload(event.getPayload(), FeedDeleteMessage.class);

            return OutboxRoute.builder()
                    .exchange(RabbitMQConfig.FEED_DELETE_EXCHANGE)
                    .routingKey(RabbitMQConfig.FEED_DELETE_ROUTING_KEY)
                    .message(message)
                    .build();
        }


        /**
         * 热度重新计算事件。
         */
        if (OutboxEventType.HOT_SCORE_RECALCULATE_REQUESTED.getCode().equals(event.getEventType())) {
            HotScoreMessage message = deserializePayload(event.getPayload(), HotScoreMessage.class);

            return OutboxRoute.builder()
                    .exchange(RabbitMQConfig.HOT_SCORE_UPDATE_EXCHANGE)
                    .routingKey(RabbitMQConfig.HOT_SCORE_UPDATE_ROUTING_KEY)
                    .message(message)
                    .build();
        }


        /**
         * Elasticsearch 校准事件。
         */
        if (OutboxEventType.SEARCH_RECONCILE_REQUESTED.getCode().equals(event.getEventType())) {
            SearchReconcileMessage message = deserializePayload(event.getPayload(), SearchReconcileMessage.class);

            return OutboxRoute.builder()
                    .exchange(RabbitMQConfig.SEARCH_RECONCILE_EXCHANGE)
                    .routingKey(RabbitMQConfig.SEARCH_RECONCILE_ROUTING_KEY)
                    .message(message)
                    .build();
        }


        throw new IllegalArgumentException(
                "不支持的 Outbox 事件类型：" + event.getEventType()
        );
    }

    // 审核事件 payload 反序列化,用来获取审核任务 ID
    private ModerationTaskMessage
    deserializeModerationMessage(String payload) {
        try {
            return objectMapper.readValue(
                    payload,
                    ModerationTaskMessage.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "审核事件 payload 反序列化失败",
                    exception
            );
        }
    }
    /**
     * 反序列化通知事件 payload。
     */
    private NotificationEventMessage
    deserializeNotificationMessage(
            String payload
    ) {
        try {
            return objectMapper.readValue(
                    payload,
                    NotificationEventMessage.class
            );

        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "通知事件 payload 反序列化失败",
                    exception
            );
        }
    }
    private <T> T deserializePayload(String payload, Class<T> messageType) {
        try {
            return objectMapper.readValue(payload, messageType);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox 事件 payload 反序列化失败", exception);
        }
    }
}