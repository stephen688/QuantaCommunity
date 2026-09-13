package com.quanta.demo0.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.entity.Notification;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.enums.NotificationType;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.mq.producer.NotificationProducer;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.NotificationConsumeService;
import com.quanta.demo0.service.UserAccessStateService;
import com.quanta.demo0.vo.NotificationVO;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
class NotificationConsumer {

    private static final String CONSUMER_NAME = "notification-consumer";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_SECONDS = 60L;

    private final String instanceId = "notification-" + UUID.randomUUID();

    @Autowired
    private InboxEventService inboxEventService;

    @Autowired
    private NotificationConsumeService notificationConsumeService;

    @Autowired
    private NotificationProducer notificationProducer;

    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;


    /**
     * MQ线程没有SecurityContext，
     * 因此通过独立服务检查接收人的实时推送资格。
     */
    @Autowired
    private UserAccessStateService userAccessStateService;




    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void handleNotificationMessage(NotificationEventMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        if (!StringUtils.hasText(message.getEventId())) {
            log.error("通知消息缺少 eventId，转入死信队列，recipientUserId={}", message.getRecipientUserId());
            sendDeadMessage(message, channel, deliveryTag);
            return;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);

            switch (acquireResult) {
                case ALREADY_SUCCESS -> {
                    log.info("通知事件已经处理成功，直接 ACK，eventId={}", message.getEventId());
                    channel.basicAck(deliveryTag, false);
                }

                case BUSY -> sendBusyMessageToRetry(message, channel, deliveryTag);

                case DEAD -> sendDeadMessage(message, channel, deliveryTag);

                case ACQUIRED -> processAcquiredMessage(message, channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("通知消息抢占异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void processAcquiredMessage(NotificationEventMessage message, Channel channel, long deliveryTag) {
        try {
            Notification notification = notificationConsumeService.saveAndMarkSuccess(message, CONSUMER_NAME, instanceId);

            // 数据库事务已经提交，WebSocket 失败不能回滚通知。
            pushWebSocketBestEffort(message, notification);

            channel.basicAck(deliveryTag, false);
            log.info("通知消息处理成功，eventId={}, notificationId={}", message.getEventId(), notification.getId());
        } catch (Exception e) {
            log.error("通知消息处理失败，eventId={}", message.getEventId(), e);
            handleProcessingFailure(message, e, channel, deliveryTag);
        }
    }

    private void handleProcessingFailure(NotificationEventMessage message, Exception exception, Channel channel, long deliveryTag) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String lastError = exception.getClass().getSimpleName() + "：" + exception.getMessage();

        try {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                boolean updated = inboxEventService.markDead(CONSUMER_NAME, message.getEventId(), instanceId, lastError);

                if (!updated) {
                    throw new IllegalStateException("通知 Inbox 已失去处理权，不能标记 DEAD");
                }

                sendDeadMessage(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS);

            boolean updated = inboxEventService.markRetry(CONSUMER_NAME, message.getEventId(), instanceId, nextRetryCount, nextRetryTime, lastError);

            if (!updated) {
                throw new IllegalStateException("通知 Inbox 已失去处理权，不能标记 RETRYING");
            }

            message.setRetryCount(nextRetryCount);

            if (notificationProducer.sendRetryTask(message)) {
                channel.basicAck(deliveryTag, false);
                log.warn("通知消息等待重试，eventId={}, retryCount={}", message.getEventId(), nextRetryCount);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("通知失败状态处理异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendBusyMessageToRetry(NotificationEventMessage message, Channel channel, long deliveryTag) {
        try {
            if (notificationProducer.sendRetryTask(message)) {
                channel.basicAck(deliveryTag, false);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("繁忙通知进入重试队列失败，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendDeadMessage(NotificationEventMessage message, Channel channel, long deliveryTag) {
        try {
            if (notificationProducer.sendDeadTask(message)) {
                channel.basicAck(deliveryTag, false);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("通知进入死信队列失败，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    /**
     * WebSocket 只是实时展示能力。
     * 推送失败不会删除数据库通知，也不会让 MQ 重试。
     */
    private void pushWebSocketBestEffort(NotificationEventMessage message, Notification notification) {

        Long recipientUserId = message.getRecipientUserId();

        /*
         * 数据库通知已经保存成功。
         * 封禁只阻止实时推送，不删除通知事实，也不触发MQ重试。
         */
        if (!userAccessStateService
                .canReceiveRealtimePush(recipientUserId)) {
            log.info(
                    "接收用户当前不可实时推送，跳过WebSocket通知，eventId={}",
                    message.getEventId()
            );
            return;
        }



        try {
            NotificationType typeEnum = NotificationType.getByCode(message.getType());

            NotificationVO pushVO = NotificationVO.builder()
                    .id(notification.getId())
                    .actorUserId(message.getActorUserId())
                    .type(message.getType())
                    .typeDesc(typeEnum != null ? typeEnum.getDesc() : "")
                    .content(message.getContent())
                    .payload(JSON.toJSONString(message.getPayload()))
                    .isRead(0)
                    .createTime(notification.getCreateTime())
                    .build();

            simpMessagingTemplate.convertAndSendToUser(String.valueOf(recipientUserId), "/queue/notifications", pushVO);
        } catch (Exception e) {
            log.warn("WebSocket 通知推送失败，用户仍可从通知列表读取，notificationId={}", notification.getId(), e);
        }
    }

    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("通知消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
