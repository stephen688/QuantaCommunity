package com.quanta.demo0.notification.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.notification.config.NotificationMQConfig;
import com.quanta.demo0.notification.entity.Notification;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationProducer;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.quanta.demo0.notification.service.NotificationConsumeService;
import com.quanta.demo0.platform.security.service.UserAccessStateService;
import com.quanta.demo0.notification.vo.NotificationVO;
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

/**
 * 通知消息消费者：站内通知链路的落库终点。
 *
 * 上一棒：业务域（审核通过/驳回、被回复、被点赞、被关注等）在业务事务里把
 * NotificationEventMessage 写进 Outbox 表，OutboxDispatcher 定时把它投递到
 * notification.queue；本类从这里接手，把通知写入 tb_notification 并尽力推送 WebSocket。
 *
 * ============================================================
 * 【为什么通知要经 MQ 异步落库，而不是业务代码里直接 insert？】
 * ============================================================
 * 直接插入会让审核、点赞等业务方法背上一次同步 DB 写，通知存储抖动会拖慢
 * 业务主流程；而 Outbox 事件与业务同事务提交（见 NotificationEventProducer），
 * 事务提交后即使应用崩溃，OutboxDispatcher 也会重投，通知不丢。
 * **业务事务只保证"事件不丢"，落库这一步的可靠性由本类的 Inbox 幂等 + 手动 ACK 兜底。**
 *
 * ============================================================
 * 【重复投递为什么不会写出两条一样的通知？】
 * ============================================================
 * 每条消息带全局唯一 eventId。消费前先调 InboxEventService.acquire 抢占处理权：
 * tb_inbox_event 对 (consumer_name, event_id) 有唯一键，抢占结果分四态
 * （ACQUIRED/ALREADY_SUCCESS/BUSY/DEAD，见 InboxAcquireResult），只有首次
 * ACQUIRED 才执行落库；落库与 Inbox 标记 SUCCESS 在同一个事务
 * （NotificationConsumeService.saveAndMarkSuccess），中途崩溃则两者一起回滚，
 * 重投后重新处理——不会出现"通知已插入但事件被标记成功"的重复或丢失。
 */
@Service
@Slf4j
class NotificationConsumer {

    // Inbox 表的消费者身份：同一事件对每个 consumer_name 独立记账、独立幂等
    private static final String CONSUMER_NAME = "notification-consumer";
    // 落库失败的最大重试次数，超过后 Inbox 标记 DEAD、消息转入停放死信队列
    private static final int MAX_RETRY_COUNT = 3;
    // 重试间隔 60 秒：与 retry 队列的 x-message-ttl=60000 对齐，消息躺满 60 秒死信回主队列
    private static final long RETRY_DELAY_SECONDS = 60L;

    // 本 JVM 实例标识：Inbox 租约按实例记账，后续 markSuccess/markRetry 必须由持租约的同一实例执行
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




    /**
     * 消费入口：先幂等抢占处理权，再按抢占结果分派。
     *
     * 三个参数由 Spring AMQP 自动装配：message 是 JSON 反序列化后的业务消息
     * （全局用 Jackson2JsonMessageConverter），mqMessage 提供 deliveryTag，
     * channel 用于手动 ACK。
     *
     * 【每个分支都必须终结消息】
     * 全局手动 ACK（application.yml 的 acknowledge-mode: manual，prefetch=1），
     * 任何分支漏掉 ack/nack，消息会一直占用未确认窗口，连接断开后重投。
     * - 缺 eventId：无法记账，直接送停放死信队列；
     * - ALREADY_SUCCESS：该事件已成功处理过（重复投递），直接 ACK 去重；
     * - BUSY：其他实例持有租约，转投 retry 队列躺 60 秒再回主队列；
     * - DEAD：Inbox 已判死，转停放队列，不再处理；
     * - ACQUIRED：抢到租约，真正落库（processAcquiredMessage）。
     * 抢占本身抛异常说明状态拿不准，只能 nackAndRequeue 原样退回。
     */
    @RabbitListener(queues = NotificationMQConfig.NOTIFICATION_QUEUE)
    public void handleNotificationMessage(NotificationEventMessage message, Message mqMessage, Channel channel) {
        long deliveryTag = mqMessage.getMessageProperties().getDeliveryTag();

        if (!StringUtils.hasText(message.getEventId())) {
            log.error("通知消息缺少 eventId，转入死信队列，recipientUserId={}", message.getRecipientUserId());
            sendDeadMessage(message, channel, deliveryTag);
            return;
        }

        try {
            InboxAcquireResult acquireResult = inboxEventService.acquire(CONSUMER_NAME, instanceId, message);

            // 四种抢占结果对应四种消息去向，枚举定义见 InboxAcquireResult
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

    /**
     * 租约在手的处理路径：落库 → 推 WebSocket → ACK。
     *
     * 【落库与 Inbox 记账必须原子】
     * saveAndMarkSuccess 在一个事务里 insert 通知 + 给 Inbox 标 SUCCESS；
     * 事务提交后即使推送或 ACK 阶段失败，通知也不会重复落库——重投的消息
     * 会在 acquire 阶段命中 ALREADY_SUCCESS 直接 ACK。
     */
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

    /**
     * 落库失败后的重试/判死决策。
     *
     * 【状态变更必须证明租约还在自己手里】
     * markRetry/markDead 的 UPDATE 都带 locked_by 条件（InboxEventMapper.xml），
     * 返回 false 说明 60 秒处理权已被其他实例抢走，此时不能转投消息，
     * 只能抛异常走 nackAndRequeue，把消息还回队列让新持有者处理。
     *
     * 【重试计数以消息体为准】
     * retryCount 记在消息里随消息转投 retry 队列（Inbox 行上的 retry_count 同步更新），
     * 下次消费是否判死看的是 message.getRetryCount() 是否达到 MAX_RETRY_COUNT=3。
     */
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

    /**
     * BUSY 场景：其他实例正在处理，转投 retry 队列躺 60 秒后死信回主队列再看。
     * 转投失败（确认发布返回 false）则 nack 重新入队，消息不丢。
     */
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

    /**
     * 终点站：把消息转投 DLX 停放队列（重试耗尽、Inbox 已 DEAD、或缺 eventId 无法记账的消息）。
     * 缺 eventId 的消息没有任何 Inbox 记录，只能靠这里保留现场供排查。
     */
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

            // 服务端推 /user/queue/notifications：WebSocketConfig 的 user 前缀(/user) + 简单代理(/queue)，
            // 客户端订阅 /user/queue/notifications，按 Principal（即 userId）定向投递
            simpMessagingTemplate.convertAndSendToUser(String.valueOf(recipientUserId), "/queue/notifications", pushVO);
        } catch (Exception e) {
            log.warn("WebSocket 通知推送失败，用户仍可从通知列表读取，notificationId={}", notification.getId(), e);
        }
    }

    /**
     * nack 并 requeue：消息原样回主队列重新投递。
     *
     * 【只用于状态不明场景】抢占异常、失去租约、转投失败时宁可重投，
     * 由下一次消费重新抢占裁决。注意 requeue=true 是立即重投、没有延迟：
     * 若依赖（数据库等）持续不可用，这里会紧循环重试；而抢占成功后的
     * 并发安全由 Inbox 租约保证，不依赖 nack 的时机。
     */
    private void nackAndRequeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            log.error("通知消息 NACK 失败，deliveryTag={}", deliveryTag, e);
        }
    }
}
