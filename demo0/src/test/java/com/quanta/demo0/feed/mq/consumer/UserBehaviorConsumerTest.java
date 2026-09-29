package com.quanta.demo0.feed.mq.consumer;

import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.feed.mq.message.UserBehaviorMessage;
import com.quanta.demo0.feed.mq.producer.UserBehaviorProducer;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.quanta.demo0.feed.service.UserInterestProfileService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UserBehaviorConsumer 的 Inbox 四态消费与权重换算测试（推荐流个性化 D2/D3）。
 * 断言：四态分支与 FeedPushConsumer 模板一致（ack/重试/死信/处理）；
 * 四种 behaviorType 按 RecommendProperties.Profile 注入权重调用 applyBehavior
 * （03 Task 3.1 收口后权重唯一真源为配置，含自定义权重生效验证）；
 * applyBehavior 对已删帖跳过不抛异常（mock 不抛即代表 skip），视为成功不重试。
 */
class UserBehaviorConsumerTest {

    private static final Long USER_ID = 3L;
    private static final Long CONTENT_ID = 10L;

    private final InboxEventService inboxEventService = mock(InboxEventService.class);
    private final UserInterestProfileService userProfileService = mock(UserInterestProfileService.class);
    private final UserBehaviorProducer userBehaviorProducer = mock(UserBehaviorProducer.class);
    private final Channel channel = mock(Channel.class);

    /** 权重配置：默认值 2.0/3.0/4.0/1.0，个别用例单独覆盖自定义权重 */
    private final RecommendProperties recommendProperties = new RecommendProperties();

    private final UserBehaviorConsumer consumer =
            new UserBehaviorConsumer(inboxEventService, userProfileService, userBehaviorProducer, recommendProperties);

    private UserBehaviorMessage message(String behaviorType, Integer retryCount) {
        return UserBehaviorMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("USER_BEHAVIOR_REQUESTED")
                .userId(USER_ID)
                .contentId(CONTENT_ID)
                .behaviorType(behaviorType)
                .occurredAt(LocalDateTime.now())
                .retryCount(retryCount)
                .build();
    }

    private Message mqMessage(long deliveryTag) {
        return MessageBuilder.withBody(new byte[0]).setDeliveryTag(deliveryTag).build();
    }

    @Test
    void 已成功重复投递_仅ack不重复累加画像() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ALREADY_SUCCESS);

        consumer.handleUserBehaviorMessage(message, mqMessage(1L), channel);

        verify(channel).basicAck(1L, false);
        verifyNoInteractions(userProfileService);
    }

    @Test
    void 租约被占BUSY_转发重试队列并ack() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.BUSY);
        when(userBehaviorProducer.sendRetryTask(message)).thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(2L), channel);

        verify(userBehaviorProducer).sendRetryTask(message);
        verify(channel).basicAck(2L, false);
        verifyNoInteractions(userProfileService);
    }

    @Test
    void 已死信重复投递_转发死信队列并ack() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.DEAD);
        when(userBehaviorProducer.sendDeadTask(message)).thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(3L), channel);

        verify(userBehaviorProducer).sendDeadTask(message);
        verify(channel).basicAck(3L, false);
        verifyNoInteractions(userProfileService);
    }

    @Test
    void LIKE消息_按权重2_0累加画像并ack() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(eq("user-behavior-consumer"), eq(message.getEventId()), anyString()))
                .thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(4L), channel);

        // mock 的 applyBehavior 不抛异常即代表画像服务"跳过已删帖"语义——视为成功，不重试
        verify(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 2.0);
        verify(inboxEventService).markSuccess(eq("user-behavior-consumer"), eq(message.getEventId()), anyString());
        verify(channel).basicAck(4L, false);
    }

    @Test
    void COLLECT消息_按权重3_0累加画像() throws Exception {
        UserBehaviorMessage message = message("COLLECT", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(eq("user-behavior-consumer"), eq(message.getEventId()), anyString()))
                .thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(5L), channel);

        verify(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 3.0);
        verify(channel).basicAck(5L, false);
    }

    @Test
    void COMMENT消息_按权重4_0累加画像() throws Exception {
        UserBehaviorMessage message = message("COMMENT", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(eq("user-behavior-consumer"), eq(message.getEventId()), anyString()))
                .thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(6L), channel);

        verify(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 4.0);
        verify(channel).basicAck(6L, false);
    }

    @Test
    void VIEW消息_按权重1_0累加画像() throws Exception {
        UserBehaviorMessage message = message("VIEW", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(eq("user-behavior-consumer"), eq(message.getEventId()), anyString()))
                .thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(7L), channel);

        verify(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 1.0);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void 缺eventId_直接死信不触碰Inbox与画像() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        message.setEventId(null);
        when(userBehaviorProducer.sendDeadTask(message)).thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(8L), channel);

        verify(userBehaviorProducer).sendDeadTask(message);
        verify(channel).basicAck(8L, false);
        verifyNoInteractions(inboxEventService, userProfileService);
    }

    @Test
    void 处理异常_登记一次重试并转发重试队列() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        doThrow(new RuntimeException("redis down"))
                .when(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 2.0);
        when(inboxEventService.markRetry(
                eq("user-behavior-consumer"), eq(message.getEventId()), anyString(),
                eq(1), any(LocalDateTime.class), anyString()))
                .thenReturn(true);
        when(userBehaviorProducer.sendRetryTask(message)).thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(9L), channel);

        verify(inboxEventService).markRetry(
                eq("user-behavior-consumer"), eq(message.getEventId()), anyString(),
                eq(1), any(LocalDateTime.class), anyString());
        verify(userBehaviorProducer).sendRetryTask(message);
        verify(channel).basicAck(9L, false);
    }

    @Test
    void 重试超限_标记DEAD并转发死信队列() throws Exception {
        UserBehaviorMessage message = message("LIKE", 3);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        doThrow(new RuntimeException("redis down"))
                .when(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 2.0);
        when(inboxEventService.markDead(eq("user-behavior-consumer"), eq(message.getEventId()), anyString(), anyString()))
                .thenReturn(true);
        when(userBehaviorProducer.sendDeadTask(message)).thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(10L), channel);

        verify(inboxEventService).markDead(eq("user-behavior-consumer"), eq(message.getEventId()), anyString(), anyString());
        verify(userBehaviorProducer).sendDeadTask(message);
        verify(channel).basicAck(10L, false);
    }

    @Test
    void 重试转发失败_nackRequeue让MQ重新投递() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        doThrow(new RuntimeException("redis down"))
                .when(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 2.0);
        when(inboxEventService.markRetry(
                eq("user-behavior-consumer"), eq(message.getEventId()), anyString(),
                eq(1), any(LocalDateTime.class), anyString()))
                .thenReturn(true);
        when(userBehaviorProducer.sendRetryTask(message)).thenReturn(false);

        consumer.handleUserBehaviorMessage(message, mqMessage(11L), channel);

        verify(channel).basicNack(11L, false, true);
    }

    @Test
    void 死信转发失败_nackRequeue让MQ重新投递() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.DEAD);
        when(userBehaviorProducer.sendDeadTask(message)).thenReturn(false);

        consumer.handleUserBehaviorMessage(message, mqMessage(12L), channel);

        verify(channel).basicNack(12L, false, true);
    }

    @Test
    void markSuccess失去租约_不误ack进入失败处理() throws Exception {
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        // 画像累加成功但 Inbox 租约已被接管，markSuccess 返回 false
        when(inboxEventService.markSuccess(eq("user-behavior-consumer"), eq(message.getEventId()), anyString()))
                .thenReturn(false);
        when(inboxEventService.markRetry(
                eq("user-behavior-consumer"), eq(message.getEventId()), anyString(),
                eq(1), any(LocalDateTime.class), anyString()))
                .thenReturn(true);
        when(userBehaviorProducer.sendRetryTask(message)).thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(13L), channel);

        // 失去处理权时不能直接 ack，必须走失败链路（markRetry + 转发重试）
        verify(inboxEventService).markRetry(
                eq("user-behavior-consumer"), eq(message.getEventId()), anyString(),
                eq(1), any(LocalDateTime.class), anyString());
        verify(userBehaviorProducer).sendRetryTask(message);
        verify(channel).basicAck(13L, false);
    }

    @Test
    void 非法behaviorType_处理失败进入重试链路() throws Exception {
        // Outbox 侧已校验白名单，此处防御：非法类型消息按失败处理，不累加画像
        UserBehaviorMessage message = message("SHARE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markRetry(
                eq("user-behavior-consumer"), eq(message.getEventId()), anyString(),
                eq(1), any(LocalDateTime.class), anyString()))
                .thenReturn(true);
        when(userBehaviorProducer.sendRetryTask(message)).thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(14L), channel);

        verify(userProfileService, org.mockito.Mockito.never())
                .applyBehavior(any(), any(), org.mockito.ArgumentMatchers.anyDouble());
        verify(userBehaviorProducer).sendRetryTask(message);
    }

    @Test
    void 自定义配置权重_消费时按注入值累加画像() throws Exception {
        // 03 Task 3.1 收口验证：修改配置对象后消费即时生效（消费时读取，非构造期快照）
        recommendProperties.getProfile().setLikeWeight(7.5);
        UserBehaviorMessage message = message("LIKE", 0);
        when(inboxEventService.acquire(eq("user-behavior-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(eq("user-behavior-consumer"), eq(message.getEventId()), anyString()))
                .thenReturn(true);

        consumer.handleUserBehaviorMessage(message, mqMessage(15L), channel);

        verify(userProfileService).applyBehavior(USER_ID, CONTENT_ID, 7.5);
        verify(channel).basicAck(15L, false);
    }
}
