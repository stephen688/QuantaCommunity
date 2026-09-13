package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.mq.message.FeedDeleteMessage;
import com.quanta.demo0.mq.producer.FeedDeleteProducer;
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
public class FeedDeleteConsumer {

    private static final String CONSUMER_NAME = "feed-delete-consumer";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_SECONDS = 60L;
    private final String instanceId = "feed-delete-" + UUID.randomUUID();

    @Autowired
    private FollowService followService;
    @Autowired
    private InboxEventService inboxEventService;
    @Autowired
    private FeedDeleteProducer feedDeleteProducer;

    @RabbitListener(queues = RabbitMQConfig.FEED_DELETE_QUEUE)
    public void handleFeedDeleteMessage(FeedDeleteMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        if (!StringUtils.hasText(message.getEventId())) {
            log.error("Feed 删除消息缺少 eventId，转入死信队列，contentId={}", message.getContentId());
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
            log.error("Feed 删除消息抢占异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void processAcquiredMessage(FeedDeleteMessage message, Channel channel, long deliveryTag) {
        try {
            reconcile(message);
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("Feed 删除 Inbox 已失去处理权，不能标记 SUCCESS");
            }
            channel.basicAck(deliveryTag, false);
            log.info("Feed 删除消息处理成功，eventId={}, contentId={}", message.getEventId(), message.getContentId());
        } catch (Exception e) {
            log.error("Feed 删除消息处理失败，eventId={}", message.getEventId(), e);
            handleProcessingFailure(message, e, channel, deliveryTag);
        }
    }

    private void handleProcessingFailure(FeedDeleteMessage message, Exception exception, Channel channel, long deliveryTag) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String lastError = exception.getClass().getSimpleName() + "：" + exception.getMessage();
        try {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                if (!inboxEventService.markDead(CONSUMER_NAME, message.getEventId(), instanceId, lastError)) {
                    throw new IllegalStateException("Feed 删除 Inbox 已失去处理权，不能标记 DEAD");
                }
                sendDeadMessage(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            if (!inboxEventService.markRetry(CONSUMER_NAME, message.getEventId(), instanceId, nextRetryCount, LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS), lastError)) {
                throw new IllegalStateException("Feed 删除 Inbox 已失去处理权，不能标记 RETRYING");
            }
            message.setRetryCount(nextRetryCount);
            if (feedDeleteProducer.sendRetryTask(message)) {
                channel.basicAck(deliveryTag, false);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("Feed 删除失败状态处理异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void reconcile(FeedDeleteMessage message) {
        // 与新增消费者共用校准方法，以 MySQL 当前状态决定最终是加入还是删除。
        followService.reconcileContentFeed(message.getContentId(), message.getPublishUserId(), message.getContentType(), null);
    }

    private void sendBusyMessageToRetry(FeedDeleteMessage message, Channel channel, long deliveryTag) {
        if (feedDeleteProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendDeadMessage(FeedDeleteMessage message, Channel channel, long deliveryTag) {
        if (feedDeleteProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Feed 删除消息 ACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("Feed 删除消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
