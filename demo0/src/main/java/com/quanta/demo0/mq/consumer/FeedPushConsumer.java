package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.mq.message.FeedPushMessage;
import com.quanta.demo0.mq.producer.FeedPushProducer;
import com.quanta.demo0.service.FollowService;
import com.quanta.demo0.service.InboxEventService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
public class FeedPushConsumer {

    private static final String CONSUMER_NAME = "feed-push-consumer";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_SECONDS = 60L;
    private final String instanceId = "feed-push-" + UUID.randomUUID();

    @Autowired
    private FollowService followService;
    @Autowired
    private InboxEventService inboxEventService;
    @Autowired
    private FeedPushProducer feedPushProducer;

    @RabbitListener(queues = RabbitMQConfig.FEED_PUSH_QUEUE)
    public void handleFeedPushMessage(FeedPushMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        if (!StringUtils.hasText(message.getEventId())) {
            log.error("Feed 推送消息缺少 eventId，转入死信队列，contentId={}", message.getContentId());
            sendDeadMessage(message, channel, deliveryTag);
            return;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);
            switch (acquireResult) {
                case ALREADY_SUCCESS -> channel.basicAck(deliveryTag, false);
                case BUSY -> sendBusyMessageToRetry(message, channel, deliveryTag);
                case DEAD -> sendDeadMessage(message, channel, deliveryTag);
                case ACQUIRED -> processAcquiredMessage(message, channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("Feed 推送消息抢占异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void processAcquiredMessage(FeedPushMessage message, Channel channel, long deliveryTag) {
        try {
            reconcile(message);
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("Feed 推送 Inbox 已失去处理权，不能标记 SUCCESS");
            }
            channel.basicAck(deliveryTag, false);
            log.info("Feed 推送消息处理成功，eventId={}, contentId={}", message.getEventId(), message.getContentId());
        } catch (Exception e) {
            log.error("Feed 推送消息处理失败，eventId={}", message.getEventId(), e);
            handleProcessingFailure(message, e, channel, deliveryTag);
        }
    }

    private void handleProcessingFailure(FeedPushMessage message, Exception exception, Channel channel, long deliveryTag) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String lastError = exception.getClass().getSimpleName() + "：" + exception.getMessage();
        try {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                if (!inboxEventService.markDead(CONSUMER_NAME, message.getEventId(), instanceId, lastError)) {
                    throw new IllegalStateException("Feed 推送 Inbox 已失去处理权，不能标记 DEAD");
                }
                sendDeadMessage(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            if (!inboxEventService.markRetry(CONSUMER_NAME, message.getEventId(), instanceId, nextRetryCount, LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS), lastError)) {
                throw new IllegalStateException("Feed 推送 Inbox 已失去处理权，不能标记 RETRYING");
            }
            message.setRetryCount(nextRetryCount);
            if (feedPushProducer.sendRetryTask(message)) {
                channel.basicAck(deliveryTag, false);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("Feed 推送失败状态处理异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void reconcile(FeedPushMessage message) {
        // 重新读取 MySQL 当前状态，乱序消息不会恢复已删除或已驳回帖子。
        followService.reconcileContentFeed(message.getContentId(), message.getPublishUserId(), message.getContentType(), message.getCreateTime());
    }

    private void sendBusyMessageToRetry(FeedPushMessage message, Channel channel, long deliveryTag) {
        if (feedPushProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendDeadMessage(FeedPushMessage message, Channel channel, long deliveryTag) {
        if (feedPushProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Feed 推送消息 ACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("Feed 推送消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
