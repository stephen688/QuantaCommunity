package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.config.RabbitMQConfig;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.mq.message.UserBehaviorMessage;
import com.quanta.demo0.mq.producer.UserBehaviorProducer;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.UserProfileService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 用户行为画像消费者（推荐流个性化 D2/D3）。
 * 职责：消费赞/藏/评/浏览四类行为事件，按权重表换算后调用画像服务累加 Redis 画像 Hash；
 * 结构逐块对齐 FeedPushConsumer 模板（Inbox acquire 四态、markSuccess/markRetry/markDead、
 * 重试上限 3 次、延迟 60 秒、缺 eventId 死信、转发失败 nack requeue）。
 * 边界：帖子已删/驳回时画像服务跳过不抛异常，本消费者视 skip 为成功，不因帖子被删无限重试；
 * 权重表本阶段为常量（03 Task 3.1 统一收口进 RecommendProperties，避免两处真源）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserBehaviorConsumer {

    private static final String CONSUMER_NAME = "user-behavior-consumer";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_SECONDS = 60L;

    /**
     * 行为权重表（D2）：LIKE=2.0 / COLLECT=3.0 / COMMENT=4.0 / VIEW=1.0。
     * 03 Task 3.1 收口进配置前的唯一真源；消息不携带权重，换算由消费侧承担。
     */
    private static final Map<String, Double> BEHAVIOR_WEIGHTS = Map.of(
            "LIKE", 2.0,
            "COLLECT", 3.0,
            "COMMENT", 4.0,
            "VIEW", 1.0
    );

    /** 实例 ID：租约归属标识，多实例部署时用于 Inbox 防抢占 */
    private final String instanceId = "user-behavior-" + UUID.randomUUID();

    private final InboxEventService inboxEventService;
    private final UserProfileService userProfileService;
    private final UserBehaviorProducer userBehaviorProducer;

    /**
     * 消费用户行为消息：先经 Inbox 幂等抢占，再按行为权重累加画像。
     * 缺 eventId 的消息直接死信；抢占异常 nack requeue 交还 MQ。
     */
    @RabbitListener(queues = RabbitMQConfig.USER_BEHAVIOR_QUEUE)
    public void handleUserBehaviorMessage(UserBehaviorMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        if (!StringUtils.hasText(message.getEventId())) {
            log.error("用户行为消息缺少 eventId，转入死信队列，userId={}, contentId={}",
                    message.getUserId(), message.getContentId());
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
            log.error("用户行为消息抢占异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void processAcquiredMessage(UserBehaviorMessage message, Channel channel, long deliveryTag) {
        try {
            reconcile(message);
            if (!inboxEventService.markSuccess(CONSUMER_NAME, message.getEventId(), instanceId)) {
                throw new IllegalStateException("用户行为 Inbox 已失去处理权，不能标记 SUCCESS");
            }
            channel.basicAck(deliveryTag, false);
            log.info("用户行为消息处理成功，eventId={}, userId={}, contentId={}, behaviorType={}",
                    message.getEventId(), message.getUserId(), message.getContentId(), message.getBehaviorType());
        } catch (Exception e) {
            log.error("用户行为消息处理失败，eventId={}", message.getEventId(), e);
            handleProcessingFailure(message, e, channel, deliveryTag);
        }
    }

    private void handleProcessingFailure(UserBehaviorMessage message, Exception exception, Channel channel, long deliveryTag) {
        int currentRetryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        String lastError = exception.getClass().getSimpleName() + "：" + exception.getMessage();
        try {
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                if (!inboxEventService.markDead(CONSUMER_NAME, message.getEventId(), instanceId, lastError)) {
                    throw new IllegalStateException("用户行为 Inbox 已失去处理权，不能标记 DEAD");
                }
                sendDeadMessage(message, channel, deliveryTag);
                return;
            }

            int nextRetryCount = currentRetryCount + 1;
            if (!inboxEventService.markRetry(CONSUMER_NAME, message.getEventId(), instanceId, nextRetryCount, LocalDateTime.now().plusSeconds(RETRY_DELAY_SECONDS), lastError)) {
                throw new IllegalStateException("用户行为 Inbox 已失去处理权，不能标记 RETRYING");
            }
            message.setRetryCount(nextRetryCount);
            if (userBehaviorProducer.sendRetryTask(message)) {
                channel.basicAck(deliveryTag, false);
            } else {
                nackAndRequeue(channel, deliveryTag);
            }
        } catch (Exception e) {
            log.error("用户行为失败状态处理异常，eventId={}", message.getEventId(), e);
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void reconcile(UserBehaviorMessage message) {
        // 权重表缺行为类型视为非法消息，按失败处理走重试/死信链路
        Double weight = BEHAVIOR_WEIGHTS.get(message.getBehaviorType());
        if (weight == null) {
            throw new IllegalStateException("未知行为类型：" + message.getBehaviorType());
        }
        // 帖子已删/驳回时画像服务内部跳过且不抛异常——视 skip 为成功，不无限重试
        userProfileService.applyBehavior(message.getUserId(), message.getContentId(), weight);
    }

    private void sendBusyMessageToRetry(UserBehaviorMessage message, Channel channel, long deliveryTag) {
        if (userBehaviorProducer.sendRetryTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void sendDeadMessage(UserBehaviorMessage message, Channel channel, long deliveryTag) {
        if (userBehaviorProducer.sendDeadTask(message)) {
            ackQuietly(channel, deliveryTag);
        } else {
            nackAndRequeue(channel, deliveryTag);
        }
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("用户行为消息 ACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }

    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("用户行为消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
