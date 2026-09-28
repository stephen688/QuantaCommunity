package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.config.TopicTagMQConfig;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.mq.producer.ContentTopicTagProducer;
import com.quanta.demo0.content.properties.ContentTopicProperties;
import com.quanta.demo0.service.ContentTopicTagService;
import com.quanta.demo0.service.InboxEventService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 内容主题标签可靠消费者。
 *
 * 职责：使用 Inbox 抢占/幂等，调用标签服务并在成功后 ACK；
 * 边界：不信任消息正文，失败按重试上限转发到死信。
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "quanta.recommend.topic-tags",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ContentTopicTagConsumer {

    private static final String CONSUMER_NAME = "content-topic-tag-consumer";

    private final String instanceId = "content-topic-tag-" + UUID.randomUUID();
    private final InboxEventService inboxEventService;
    private final ContentTopicTagService contentTopicTagService;
    private final ContentTopicTagProducer contentTopicTagProducer;
    private final ContentTopicProperties properties;

    /** 消费主题标签请求，成功写库并标记 Inbox 后才确认 Rabbit 消息。 */
    @RabbitListener(queues = TopicTagMQConfig.TOPIC_TAG_QUEUE)
    public void handle(ContentTopicTagMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();
        if (message == null || !StringUtils.hasText(message.getEventId())
                || message.getContentId() == null || message.getContentId() <= 0
                || !OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode().equals(message.getEventType())) {
            // 无法恢复的原始消息由主队列 DLX 保存，不猜事件身份、不永久 requeue。
            try { channel.basicNack(deliveryTag, false, false); }
            catch (Exception exception) { log.error("主题毒消息拒绝失败，deliveryTag={}",deliveryTag,exception); }
            return;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);
            switch (acquireResult) {
                case ALREADY_SUCCESS -> ackQuietly(channel, deliveryTag);
                case BUSY -> sendBusy(message, channel, deliveryTag);
                case DEAD -> sendDead(message, channel, deliveryTag);
                case ACQUIRED -> process(message, channel, deliveryTag);
            }
        } catch (Exception exception) {
            log.error("主题标签消息抢占异常，eventId={}", message.getEventId(), exception);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void process(ContentTopicTagMessage message, Channel channel, long deliveryTag) {
        try {
            contentTopicTagService.tagContent(message.getContentId());
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("主题标签 Inbox 已失去处理权，不能标记 SUCCESS");
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("主题标签消息处理失败，eventId={}, contentId={}", message.getEventId(), message.getContentId(), exception);
            handleFailure(message, exception, channel, deliveryTag);
        }
    }

    private void handleFailure(ContentTopicTagMessage message, Exception exception, Channel channel, long deliveryTag) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String error = exception.getClass().getSimpleName() + "：" + exception.getMessage();
        try {
            if (currentRetryCount >= properties.getMaxRetryCount()) {
                if (!inboxEventService.markDead(CONSUMER_NAME, message.getEventId(), instanceId, error)) {
                    throw new IllegalStateException("主题标签 Inbox 已失去处理权，不能标记 DEAD");
                }
                sendDead(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            if (!inboxEventService.markRetry(
                    CONSUMER_NAME,
                    message.getEventId(),
                    instanceId,
                    nextRetryCount,
                    LocalDateTime.now().plusSeconds(properties.getRetryDelaySeconds()),
                    error)) {
                throw new IllegalStateException("主题标签 Inbox 已失去处理权，不能标记 RETRYING");
            }
            message.setRetryCount(nextRetryCount);
            if (contentTopicTagProducer.sendRetryTask(message)) {
                ackQuietly(channel, deliveryTag);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception failure) {
            log.error("主题标签失败状态处理异常，eventId={}", message.getEventId(), failure);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendBusy(ContentTopicTagMessage message, Channel channel, long deliveryTag) {
        if (contentTopicTagProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendDead(ContentTopicTagMessage message, Channel channel, long deliveryTag) {
        if (message != null && contentTopicTagProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            log.error("主题标签消息 ACK 失败，deliveryTag={}", deliveryTag, exception);
        }
    }

    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception exception) {
            log.error("主题标签消息 NACK 失败，deliveryTag={}", deliveryTag, exception);
        }
    }
}
