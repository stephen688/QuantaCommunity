package com.quanta.demo0.feed.mq.consumer;

import com.quanta.demo0.feed.config.ProfileMQConfig;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.feed.mq.message.ProfileReconcileMessage;
import com.quanta.demo0.feed.mq.producer.ProfileReconcileProducer;
import com.quanta.demo0.feed.service.ExplicitPreferenceService;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.UUID;

/** 显式画像 Inbox 消费：校准当前事实→SUCCESS→ACK，失败按既有3次/60秒重试和DLQ约定处理。 */
@Service @RequiredArgsConstructor @Slf4j
public class ProfileReconcileConsumer {
    public static final String CONSUMER_NAME = "profile-reconcile-consumer";
    private final String instanceId = "profile-reconcile-" + UUID.randomUUID();
    private final InboxEventService inbox;
    private final ExplicitPreferenceService preferences;
    private final ProfileReconcileProducer producer;

    /** 每条消息先抢占 Inbox；非法事件不猜 ID，转发失败保留原消息。 */
    @RabbitListener(queues = ProfileMQConfig.PROFILE_QUEUE)
    public void handle(ProfileReconcileMessage event, Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            if (event == null || !StringUtils.hasText(event.getEventId())
                    || event.getUserId() == null || event.getUserId() <= 0
                    || !OutboxEventType.USER_PROFILE_UPDATED.getCode().equals(event.getEventType())) {
                // 不可恢复的原始消息由 broker 保留到专用 DLQ，不能制造随机身份或永久重入。
                channel.basicNack(tag, false, false);
                return;
            }
            InboxAcquireResult acquired = inbox.acquire(CONSUMER_NAME, instanceId, event);
            switch (acquired) {
                case ALREADY_SUCCESS -> channel.basicAck(tag, false);
                case BUSY -> forwardOrRequeue(producer.sendRetryTask(event), channel, tag);
                case DEAD -> forwardOrRequeue(producer.sendDeadTask(event), channel, tag);
                case ACQUIRED -> process(event, channel, tag);
            }
        } catch (Exception exception) {
            log.error("显式画像消费入口失败，eventId={}", event == null ? null : event.getEventId(), exception);
            nack(channel, tag);
        }
    }

    private void process(ProfileReconcileMessage event, Channel channel, long tag) {
        try {
            preferences.reconcile(event.getUserId());
            if (!inbox.markSuccess(CONSUMER_NAME, event.getEventId(), instanceId)) {
                throw new IllegalStateException("画像 Inbox 已失去租约所有权");
            }
            channel.basicAck(tag, false);
        } catch (Exception exception) {
            int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
            String error = exception.getClass().getSimpleName();
            try {
                if (retryCount >= 3) {
                    if (!inbox.markDead(CONSUMER_NAME, event.getEventId(), instanceId, error)) {
                        throw new IllegalStateException("画像 Inbox DEAD 更新失去所有权");
                    }
                    forwardOrRequeue(producer.sendDeadTask(event), channel, tag);
                } else {
                    if (!inbox.markRetry(CONSUMER_NAME, event.getEventId(), instanceId,
                            retryCount + 1, LocalDateTime.now().plusSeconds(60), error)) {
                        throw new IllegalStateException("画像 Inbox RETRY 更新失去所有权");
                    }
                    event.setRetryCount(retryCount + 1);
                    forwardOrRequeue(producer.sendRetryTask(event), channel, tag);
                }
            } catch (Exception retryException) {
                log.error("画像消费重试登记/转发失败，eventId={}", event.getEventId(), retryException);
                nack(channel, tag);
            }
        }
    }

    private void forwardOrRequeue(boolean confirmed, Channel channel, long tag) throws java.io.IOException {
        if (confirmed) { channel.basicAck(tag, false); }
        else { channel.basicNack(tag, false, true); }
    }
    private void nack(Channel channel, long tag) {
        try { channel.basicNack(tag, false, true); }
        catch (java.io.IOException exception) { log.error("画像 NACK 失败，deliveryTag={}", tag, exception); }
    }
}
