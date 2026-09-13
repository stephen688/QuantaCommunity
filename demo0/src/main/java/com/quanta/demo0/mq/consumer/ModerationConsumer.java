package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.annotation.ModerationDecision;
import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.mq.message.ModerationTaskMessage;
import com.quanta.demo0.mq.producer.ModerationProducer;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.service.ContentModerationService;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.ModerationResultService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审核任务消费者。
 */
@Slf4j
@Component
public class ModerationConsumer {

    /**
     * 同一个事件可以被不同消费者处理，
     * 但同一个消费者只能成功处理一次。
     */
    private static final String CONSUMER_NAME =
            "moderation-consumer";

    /**
     * 审核重试队列目前固定延迟 60 秒。
     */
    private static final long RETRY_DELAY_SECONDS = 60L;

    /**
     * 每个 Spring Boot 进程拥有不同的实例 ID。
     */
    private final String instanceId =
            "moderation-" + UUID.randomUUID();

    @Autowired
    private ContentModerationService moderationService;

    @Autowired
    private ModerationResultService moderationResultService;

    @Autowired
    private InboxEventService inboxEventService;

    @Autowired
    private ModerationProducer moderationProducer;

    @Autowired
    private AliyunModerationProperties moderationProperties;

    @RabbitListener(
            queues = RabbitMQConfig.MODERATION_QUEUE
    )
    public void handleModerationTask(
            ModerationTaskMessage task,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG)
            long deliveryTag
    ) {
        if (!StringUtils.hasText(task.getEventId())) {
            log.error("审核消息缺少 eventId，拒绝进入可靠消费链路，targetType={}, targetId={}",
                    task.getTargetType(), task.getTargetId());
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception exception) {
                log.error("无效审核消息进入死信队列失败，deliveryTag={}", deliveryTag, exception);
            }
            return;
        }

        try {
            InboxAcquireResult acquireResult =
                    inboxEventService.acquire(
                            CONSUMER_NAME,
                            instanceId,
                            task
                    );

            switch (acquireResult) {
                case ALREADY_SUCCESS -> {
                    log.info(
                            "审核事件已经处理成功，直接 ACK，eventId={}",
                            task.getEventId()
                    );

                    channel.basicAck(
                            deliveryTag,
                            false
                    );

                    return;
                }

                case BUSY -> {
                    log.info(
                            "审核事件正在被其他实例处理，进入重试队列，eventId={}",
                            task.getEventId()
                    );

                    sendBusyMessageToRetry(
                            task,
                            channel,
                            deliveryTag
                    );

                    return;
                }

                case DEAD -> {
                    log.error(
                            "审核事件已经进入 DEAD，转入死信队列，eventId={}",
                            task.getEventId()
                    );

                    channel.basicNack(
                            deliveryTag,
                            false,
                            false
                    );

                    return;
                }

                case ACQUIRED ->
                        processAcquiredMessage(
                                task,
                                channel,
                                deliveryTag
                        );
            }

        } catch (Exception e) {
            log.error(
                    "审核消息抢占异常，eventId={}",
                    task.getEventId(),
                    e
            );

            nackAndRequeue(
                    channel,
                    deliveryTag
            );
        }
    }

    /**
     * 处理已经成功抢占的消息。
     */
    private void processAcquiredMessage(
            ModerationTaskMessage task,
            Channel channel,
            long deliveryTag
    ) {
        try {
            log.info(
                    "开始处理审核任务，eventId={}, targetType={}, targetId={}",
                    task.getEventId(),
                    task.getTargetType(),
                    task.getTargetId()
            );

            ModerationResult result =
                    moderationService.moderate(task);

            /**
             * ERROR 不能标记 SUCCESS，
             * 必须进入重试或 DEAD。
             */
            if (result.getDecision()
                    == ModerationDecision.ERROR) {
                handleProcessingFailure(
                        task,
                        result,
                        result.getRejectReason(),
                        channel,
                        deliveryTag
                );
                return;
            }

            /**
             * 审核状态更新和 Inbox SUCCESS
             * 在同一个数据库事务中完成。
             */
            moderationResultService
                    .handleResultAndMarkSuccess(
                            task,
                            result,
                            CONSUMER_NAME,
                            instanceId
                    );

            /**
             * 数据库事务成功后才能 ACK。
             */
            channel.basicAck(
                    deliveryTag,
                    false
            );

            log.info(
                    "审核任务处理成功，eventId={}, targetId={}",
                    task.getEventId(),
                    task.getTargetId()
            );

        } catch (Exception e) {
            log.error(
                    "审核任务处理异常，eventId={}, targetId={}",
                    task.getEventId(),
                    task.getTargetId(),
                    e
            );

            handleProcessingFailure(
                    task,
                    null,
                    e.getClass().getSimpleName()
                            + "："
                            + e.getMessage(),
                    channel,
                    deliveryTag
            );
        }
    }

    /**
     * 失败后进入重试队列，
     * 超过最大次数后进入 DEAD 和死信队列。
     */
    private void handleProcessingFailure(
            ModerationTaskMessage task,
            ModerationResult result,
            String lastError,
            Channel channel,
            long deliveryTag
    ) {
        int currentRetryCount =
                task.getRetryCount() == null
                        ? 0
                        : task.getRetryCount();

        int maxRetryCount =
                moderationProperties.getMaxRetryCount();

        try {
            if (currentRetryCount >= maxRetryCount) {
                moderationService.saveFailedRecord(
                        task,
                        result
                );

                boolean updated =
                        inboxEventService.markDead(
                                CONSUMER_NAME,
                                task.getEventId(),
                                instanceId,
                                lastError
                        );

                if (!updated) {
                    throw new IllegalStateException(
                            "Inbox 已失去处理权，不能标记 DEAD"
                    );
                }

                /**
                 * requeue=false：
                 * 消息进入 moderation.dlx.queue。
                 */
                channel.basicNack(
                        deliveryTag,
                        false,
                        false
                );

                log.error(
                        "审核事件进入 DEAD，eventId={}, retryCount={}",
                        task.getEventId(),
                        currentRetryCount
                );

                return;
            }

            int nextRetryCount =
                    currentRetryCount + 1;

            LocalDateTime nextRetryTime =
                    LocalDateTime.now()
                            .plusSeconds(
                                    RETRY_DELAY_SECONDS
                            );

            boolean updated =
                    inboxEventService.markRetry(
                            CONSUMER_NAME,
                            task.getEventId(),
                            instanceId,
                            nextRetryCount,
                            nextRetryTime,
                            lastError
                    );

            if (!updated) {
                throw new IllegalStateException(
                        "Inbox 已失去处理权，不能标记 RETRYING"
                );
            }

            task.setRetryCount(nextRetryCount);

            boolean sent =
                    moderationProducer
                            .sendRetryTask(task);

            if (!sent) {
                /**
                 * 重试消息发送失败，
                 * 原消息不能 ACK。
                 */
                nackAndRequeue(
                        channel,
                        deliveryTag
                );
                return;
            }

            /**
             * 重试消息已经进入延迟队列，
             * 当前消息可以 ACK。
             */
            channel.basicAck(
                    deliveryTag,
                    false
            );

            log.warn(
                    "审核事件等待重试，eventId={}, retryCount={}, nextRetryTime={}",
                    task.getEventId(),
                    nextRetryCount,
                    nextRetryTime
            );

        } catch (Exception e) {
            log.error(
                    "审核失败状态处理异常，eventId={}",
                    task.getEventId(),
                    e
            );

            nackAndRequeue(
                    channel,
                    deliveryTag
            );
        }
    }

    /**
     * Inbox 正被其他实例处理时，
     * 不立即重复执行，先进入 60 秒重试队列。
     */
    private void sendBusyMessageToRetry(
            ModerationTaskMessage task,
            Channel channel,
            long deliveryTag
    ) {
        try {
            boolean sent =
                    moderationProducer
                            .sendRetryTask(task);

            if (sent) {
                channel.basicAck(
                        deliveryTag,
                        false
                );
            } else {
                nackAndRequeue(
                        channel,
                        deliveryTag
                );
            }

        } catch (Exception e) {
            log.error(
                    "繁忙消息进入重试队列失败，eventId={}",
                    task.getEventId(),
                    e
            );

            nackAndRequeue(
                    channel,
                    deliveryTag
            );
        }
    }

    private void nackAndRequeue(
            Channel channel,
            long deliveryTag
    ) {
        try {
            channel.basicNack(
                    deliveryTag,
                    false,
                    true
            );
        } catch (Exception e) {
            log.error(
                    "NACK 失败，deliveryTag={}",
                    deliveryTag,
                    e
            );
        }
    }
}
