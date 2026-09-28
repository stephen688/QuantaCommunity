package com.quanta.demo0.platform.mq.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.content.config.TopicTagMQConfig;
import com.quanta.demo0.feed.config.ProfileMQConfig;
import com.quanta.demo0.platform.mq.entity.OutboxEvent;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;

import com.quanta.demo0.comment.mq.message.BotMentionMessage;
import com.quanta.demo0.content.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.feed.mq.message.FeedDeleteMessage;
import com.quanta.demo0.feed.mq.message.FeedPushMessage;
import com.quanta.demo0.feed.mq.message.HotScoreMessage;
import com.quanta.demo0.feed.mq.message.ProfileReconcileMessage;
import com.quanta.demo0.feed.mq.message.UserBehaviorMessage;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.platform.mq.message.OutboxRoute;
import com.quanta.demo0.search.mq.message.SearchReconcileMessage;
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

        /**
         * bot 触发事件（C-1）：路由到 quantabot 拓扑。
         */
        if (OutboxEventType.BOT_MENTION_REQUESTED.getCode().equals(event.getEventType())) {
            BotMentionMessage message = deserializePayload(event.getPayload(), BotMentionMessage.class);

            return OutboxRoute.builder()
                    .exchange(RabbitMQConfig.BOT_MENTION_EXCHANGE)
                    .routingKey(RabbitMQConfig.BOT_MENTION_ROUTING_KEY)
                    .message(message)
                    .build();
        }

        /**
         * 用户行为事件（D2）：路由到 user.behavior 拓扑，画像消费者累加画像 Hash。
         */
        if (OutboxEventType.USER_BEHAVIOR_REQUESTED.getCode().equals(event.getEventType())) {
            UserBehaviorMessage message = deserializePayload(event.getPayload(), UserBehaviorMessage.class);

            return OutboxRoute.builder()
                    .exchange(RabbitMQConfig.USER_BEHAVIOR_EXCHANGE)
                    .routingKey(RabbitMQConfig.USER_BEHAVIOR_ROUTING_KEY)
                    .message(message)
                    .build();
        }


        if (OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode().equals(event.getEventType())) {
            return OutboxRoute.builder().exchange(TopicTagMQConfig.TOPIC_TAG_EXCHANGE)
                    .routingKey(TopicTagMQConfig.TOPIC_TAG_ROUTING_KEY)
                    .message(deserializePayload(event.getPayload(), ContentTopicTagMessage.class)).build();
        }
        if (OutboxEventType.USER_PROFILE_UPDATED.getCode().equals(event.getEventType())) {
            return OutboxRoute.builder().exchange(ProfileMQConfig.PROFILE_EXCHANGE)
                    .routingKey(ProfileMQConfig.PROFILE_ROUTING_KEY)
                    .message(deserializePayload(event.getPayload(), ProfileReconcileMessage.class)).build();
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
