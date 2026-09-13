package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.mq.message.HotScoreMessage;
import com.quanta.demo0.mq.producer.HotScoreUpdateProducer;
import com.quanta.demo0.service.ContentService;
import com.quanta.demo0.service.InboxEventService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 热度重新计算消费者。
 * 消息只负责提醒，最终热度始终根据 MySQL 当前数据重新计算。
 */
@Component
@Slf4j
public class HotScoreUpdateConsumer {

    private static final String CONSUMER_NAME = "hot-score-consumer";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_SECONDS = 60L;
    private final String instanceId = "hot-score-" + UUID.randomUUID();

    @Autowired
    private ContentService contentService;
    @Autowired
    private InboxEventService inboxEventService;
    @Autowired
    private HotScoreUpdateProducer hotScoreUpdateProducer;

    @RabbitListener(queues = RabbitMQConfig.HOT_SCORE_UPDATE_QUEUE)
    public void handleHotScoreUpdate(HotScoreMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        if (!StringUtils.hasText(message.getEventId())) {
            log.error("热度消息缺少 eventId，转入死信队列，contentId={}", message.getContentId());
            sendDeadMessage(message, channel, deliveryTag);
            return;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);
            switch (acquireResult) {
                case ALREADY_SUCCESS -> {
                    channel.basicAck(deliveryTag, false);
                    log.info("热度事件已经处理成功，直接 ACK，eventId={}", message.getEventId());
                }
                case BUSY -> sendBusyMessageToRetry(message, channel, deliveryTag);
                case DEAD -> sendDeadMessage(message, channel, deliveryTag);
                case ACQUIRED -> processAcquiredMessage(message, channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("热度消息抢占异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void processAcquiredMessage(HotScoreMessage message, Channel channel, long deliveryTag) {
        try {
            // Redis 使用覆盖式写入，即使消息重复执行，最终分数也不会重复累加。
            contentService.reconcileHotScore(message.getContentId());
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("热度 Inbox 已失去处理权，不能标记 SUCCESS");
            }
            channel.basicAck(deliveryTag, false);
            log.info("热度消息处理成功，eventId={}, contentId={}", message.getEventId(), message.getContentId());
        } catch (Exception e) {
            log.error("热度消息处理失败，eventId={}", message.getEventId(), e);
            handleProcessingFailure(message, e, channel, deliveryTag);
        }
    }

    private void handleProcessingFailure(HotScoreMessage message, Exception exception, Channel channel, long deliveryTag) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String lastError = exception.getClass().getSimpleName() + "：" + exception.getMessage();

        try {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                if (!inboxEventService.markDead(CONSUMER_NAME, message.getEventId(), instanceId, lastError)) {
                    throw new IllegalStateException("热度 Inbox 已失去处理权，不能标记 DEAD");
                }
                sendDeadMessage(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS);
            if (!inboxEventService.markRetry(CONSUMER_NAME, message.getEventId(), instanceId, nextRetryCount, nextRetryTime, lastError)) {
                throw new IllegalStateException("热度 Inbox 已失去处理权，不能标记 RETRYING");
            }
            message.setRetryCount(nextRetryCount);
            if (hotScoreUpdateProducer.sendRetryTask(message)) {
                channel.basicAck(deliveryTag, false);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("热度失败状态处理异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendBusyMessageToRetry(HotScoreMessage message, Channel channel, long deliveryTag) {
        if (hotScoreUpdateProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendDeadMessage(HotScoreMessage message, Channel channel, long deliveryTag) {
        if (hotScoreUpdateProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("热度消息 ACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("热度消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
