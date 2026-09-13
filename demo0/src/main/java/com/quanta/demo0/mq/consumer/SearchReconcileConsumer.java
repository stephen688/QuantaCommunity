package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.mq.message.SearchReconcileMessage;
import com.quanta.demo0.mq.producer.SearchReconcileProducer;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.SearchReconcileService;
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
 * Elasticsearch 索引校准消费者。
 *
 * 职责：
 * 1. 使用 Inbox 防止同一事件重复处理；
 * 2. 调用 Service 根据 MySQL 当前状态校准 ES；
 * 3. 处理重试、死信和 RabbitMQ ACK。
 */
@Component
@Slf4j
public class SearchReconcileConsumer {

    private static final String CONSUMER_NAME = "search-reconcile-consumer";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_SECONDS = 60L;

    /**
     * 当前应用实例的唯一标识。
     * 多个后端实例同时消费时，每个实例都不同。
     */
    private final String instanceId = "search-reconcile-" + UUID.randomUUID();

    @Autowired
    private InboxEventService inboxEventService;

    @Autowired
    private SearchReconcileService searchReconcileService;

    @Autowired
    private SearchReconcileProducer searchReconcileProducer;

    @RabbitListener(queues = RabbitMQConfig.SEARCH_RECONCILE_QUEUE)
    public void handleSearchReconcileMessage(SearchReconcileMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        // V4 是新队列，不需要兼容没有 eventId 的历史消息。
        if (!StringUtils.hasText(message.getEventId())) {
            log.error("ES 校准消息缺少 eventId，message={}", message);
            sendInvalidMessageToDead(message, channel, deliveryTag);
            return;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);

            switch (acquireResult) {
                case ALREADY_SUCCESS -> {
                    // 相同事件之前已经成功处理，不能再次操作业务。
                    channel.basicAck(deliveryTag, false);
                    log.info("ES 校准事件已经处理成功，直接 ACK，eventId={}", message.getEventId());
                }

                case BUSY -> {
                    // 其他实例仍然持有处理租约，当前消息进入延迟重试队列。
                    sendBusyMessageToRetry(message, channel, deliveryTag);
                }

                case DEAD -> {
                    // Inbox 已经是 DEAD，不再执行业务，重新送入死信队列供管理员排查。
                    sendDeadMessage(message, channel, deliveryTag);
                }

                case ACQUIRED -> {
                    // 当前实例成功取得处理权。
                    processAcquiredMessage(message, channel, deliveryTag);
                }
            }
        } catch (Exception e) {
            log.error("ES 校准消息抢占异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void processAcquiredMessage(SearchReconcileMessage message, Channel channel, long deliveryTag) {
        try {
            // Service 会重新查询 MySQL，决定 ES 应该 upsert 还是 delete。
            searchReconcileService.reconcileSearchIndex(message.getTargetType(), message.getTargetId());

            // ES 成功后再把 Inbox 标记为 SUCCESS。
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("ES 校准 Inbox 已失去处理权，不能标记 SUCCESS");
            }

            channel.basicAck(deliveryTag, false);
            log.info(
                    "ES 校准消息处理成功，eventId={}, targetType={}, targetId={}",
                    message.getEventId(),
                    message.getTargetType(),
                    message.getTargetId()
            );
        } catch (Exception e) {
            log.error("ES 校准消息处理失败，eventId={}", message.getEventId(), e);
            handleProcessingFailure(message, e, channel, deliveryTag);
        }
    }

    private void handleProcessingFailure(
            SearchReconcileMessage message,
            Exception exception,
            Channel channel,
            long deliveryTag
    ) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String lastError = exception.getClass().getSimpleName() + "：" + exception.getMessage();

        try {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                boolean updated = inboxEventService.markDead(
                        CONSUMER_NAME,
                        message.getEventId(),
                        instanceId,
                        lastError
                );

                if (!updated) {
                    throw new IllegalStateException("ES 校准 Inbox 已失去处理权，不能标记 DEAD");
                }

                sendDeadMessage(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS);

            boolean updated = inboxEventService.markRetry(
                    CONSUMER_NAME,
                    message.getEventId(),
                    instanceId,
                    nextRetryCount,
                    nextRetryTime,
                    lastError
            );

            if (!updated) {
                throw new IllegalStateException("ES 校准 Inbox 已失去处理权，不能标记 RETRYING");
            }

            message.setRetryCount(nextRetryCount);

            if (searchReconcileProducer.sendRetryTask(message)) {
                // 重试消息已经保存到 RabbitMQ，当前原消息可以 ACK。
                channel.basicAck(deliveryTag, false);
            } else {
                // 重试消息发送失败，原消息不能丢，重新放回主队列。
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("ES 校准失败状态处理异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendBusyMessageToRetry(
            SearchReconcileMessage message,
            Channel channel,
            long deliveryTag
    ) {
        if (searchReconcileProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendDeadMessage(
            SearchReconcileMessage message,
            Channel channel,
            long deliveryTag
    ) {
        if (searchReconcileProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendInvalidMessageToDead(
            SearchReconcileMessage message,
            Channel channel,
            long deliveryTag
    ) {
        if (searchReconcileProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("ES 校准消息 ACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("ES 校准消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}