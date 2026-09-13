package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.annotation.ModerationDecision;
import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.es.service.ElasticSearchService;
import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.mq.message.FeedDeleteMessage;
import com.quanta.demo0.mq.message.FeedPushMessage;
import com.quanta.demo0.mq.message.HotScoreMessage;
import com.quanta.demo0.mq.message.ModerationTaskMessage;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.mq.producer.ModerationProducer;
import com.quanta.demo0.mq.producer.NotificationProducer;
import com.quanta.demo0.mq.producer.FeedDeleteProducer;
import com.quanta.demo0.mq.producer.FeedPushProducer;
import com.quanta.demo0.mq.producer.HotScoreUpdateProducer;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.service.ContentModerationService;
import com.quanta.demo0.service.ContentService;
import com.quanta.demo0.service.FollowService;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.ModerationResultService;
import com.quanta.demo0.service.NotificationConsumeService;
import com.quanta.demo0.service.Impl.SearchReconcileServiceImpl;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsumerReliabilityTests {

    @Test
    void duplicateModerationEventOnlyAcknowledgesWithoutRunningBusinessAgain() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        ContentModerationService moderationService = mock(ContentModerationService.class);
        ModerationResultService resultService = mock(ModerationResultService.class);
        Channel channel = mock(Channel.class);
        ModerationTaskMessage task = moderationTask(0);

        when(inboxEventService.acquire(eq("moderation-consumer"), anyString(), eq(task)))
                .thenReturn(InboxAcquireResult.ALREADY_SUCCESS);

        ModerationConsumer consumer = moderationConsumer(
                inboxEventService,
                moderationService,
                resultService,
                mock(ModerationProducer.class),
                moderationProperties(3)
        );

        consumer.handleModerationTask(task, channel, 1L);

        verify(channel).basicAck(1L, false);
        verifyNoInteractions(moderationService, resultService);
    }

    @Test
    void duplicateNotificationEventOnlyAcknowledgesWithoutSavingAgain() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        NotificationConsumeService consumeService = mock(NotificationConsumeService.class);
        Channel channel = mock(Channel.class);
        NotificationEventMessage message = notificationMessage();
        Message mqMessage = MessageBuilder.withBody(new byte[0]).setDeliveryTag(2L).build();

        when(inboxEventService.acquire(eq("notification-consumer"), anyString(), eq(message)))
                .thenReturn(InboxAcquireResult.ALREADY_SUCCESS);

        NotificationConsumer consumer = new NotificationConsumer();
        ReflectionTestUtils.setField(consumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(consumer, "notificationConsumeService", consumeService);
        ReflectionTestUtils.setField(consumer, "notificationProducer", mock(NotificationProducer.class));
        ReflectionTestUtils.setField(consumer, "simpMessagingTemplate", mock(SimpMessagingTemplate.class));

        consumer.handleNotificationMessage(message, mqMessage, channel);

        verify(channel).basicAck(2L, false);
        verifyNoInteractions(consumeService);
    }

    @Test
    void exhaustedModerationRetryMarksInboxDeadAndRejectsToDlq() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        ContentModerationService moderationService = mock(ContentModerationService.class);
        ModerationResultService resultService = mock(ModerationResultService.class);
        Channel channel = mock(Channel.class);
        ModerationTaskMessage task = moderationTask(3);
        ModerationResult failedResult = ModerationResult.builder()
                .decision(ModerationDecision.ERROR)
                .rejectReason("模拟审核服务异常")
                .build();

        when(inboxEventService.acquire(eq("moderation-consumer"), anyString(), eq(task)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(moderationService.moderate(task)).thenReturn(failedResult);
        when(inboxEventService.markDead(
                eq("moderation-consumer"),
                eq(task.getEventId()),
                anyString(),
                eq("模拟审核服务异常")
        )).thenReturn(true);

        ModerationConsumer consumer = moderationConsumer(
                inboxEventService,
                moderationService,
                resultService,
                mock(ModerationProducer.class),
                moderationProperties(3)
        );

        consumer.handleModerationTask(task, channel, 3L);

        verify(moderationService).saveFailedRecord(task, failedResult);
        verify(inboxEventService).markDead(
                eq("moderation-consumer"),
                eq(task.getEventId()),
                anyString(),
                eq("模拟审核服务异常")
        );
        verify(channel).basicNack(3L, false, false);
        verifyNoInteractions(resultService);
    }

    @Test
    void outOfOrderFeedEventsAlwaysReconcileFromCurrentBusinessState() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        FollowService followService = mock(FollowService.class);
        Channel channel = mock(Channel.class);
        FeedDeleteMessage deleteMessage = FeedDeleteMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("FEED_DELETE_REQUESTED")
                .contentId(10L)
                .publishUserId(1L)
                .contentType(2)
                .retryCount(0)
                .build();
        FeedPushMessage pushMessage = FeedPushMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("FEED_PUSH_REQUESTED")
                .contentId(10L)
                .publishUserId(1L)
                .contentType(2)
                .createTime(123L)
                .retryCount(0)
                .build();

        when(inboxEventService.acquire(eq("feed-delete-consumer"), anyString(), eq(deleteMessage)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.acquire(eq("feed-push-consumer"), anyString(), eq(pushMessage)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(anyString(), anyString(), anyString())).thenReturn(true);

        FeedDeleteConsumer deleteConsumer = new FeedDeleteConsumer();
        ReflectionTestUtils.setField(deleteConsumer, "followService", followService);
        ReflectionTestUtils.setField(deleteConsumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(deleteConsumer, "feedDeleteProducer", mock(FeedDeleteProducer.class));

        FeedPushConsumer pushConsumer = new FeedPushConsumer();
        ReflectionTestUtils.setField(pushConsumer, "followService", followService);
        ReflectionTestUtils.setField(pushConsumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(pushConsumer, "feedPushProducer", mock(FeedPushProducer.class));

        deleteConsumer.handleFeedDeleteMessage(deleteMessage, delivery(4L), channel);
        pushConsumer.handleFeedPushMessage(pushMessage, delivery(5L), channel);

        verify(followService).reconcileContentFeed(10L, 1L, 2, null);
        verify(followService).reconcileContentFeed(10L, 1L, 2, 123L);
        verify(channel).basicAck(4L, false);
        verify(channel).basicAck(5L, false);
    }

    @Test
    void repeatedHotScoreAndSearchEventsUseCurrentMySqlSnapshot() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        ContentService contentService = mock(ContentService.class);
        Channel channel = mock(Channel.class);
        HotScoreMessage first = hotScoreMessage();
        HotScoreMessage second = hotScoreMessage();

        when(inboxEventService.acquire(eq("hot-score-consumer"), anyString(), any(HotScoreMessage.class)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(anyString(), anyString(), anyString())).thenReturn(true);

        HotScoreUpdateConsumer consumer = new HotScoreUpdateConsumer();
        ReflectionTestUtils.setField(consumer, "contentService", contentService);
        ReflectionTestUtils.setField(consumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(consumer, "hotScoreUpdateProducer", mock(HotScoreUpdateProducer.class));

        consumer.handleHotScoreUpdate(first, delivery(6L), channel);
        consumer.handleHotScoreUpdate(second, delivery(7L), channel);

        verify(contentService, times(2)).reconcileHotScore(10L);

        ElasticSearchService elasticSearchService = mock(ElasticSearchService.class);
        SearchReconcileServiceImpl searchService = new SearchReconcileServiceImpl(elasticSearchService);
        searchService.reconcileSearchIndex(ModerationTargetType.CONTENT.name(), 10L);
        searchService.reconcileSearchIndex(ModerationTargetType.CONTENT.name(), 10L);

        verify(elasticSearchService, times(2)).upsertByContentId(10L);
    }

    private ModerationConsumer moderationConsumer(
            InboxEventService inboxEventService,
            ContentModerationService moderationService,
            ModerationResultService resultService,
            ModerationProducer producer,
            AliyunModerationProperties properties
    ) {
        ModerationConsumer consumer = new ModerationConsumer();
        ReflectionTestUtils.setField(consumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(consumer, "moderationService", moderationService);
        ReflectionTestUtils.setField(consumer, "moderationResultService", resultService);
        ReflectionTestUtils.setField(consumer, "moderationProducer", producer);
        ReflectionTestUtils.setField(consumer, "moderationProperties", properties);
        return consumer;
    }

    private AliyunModerationProperties moderationProperties(int maxRetryCount) {
        AliyunModerationProperties properties = new AliyunModerationProperties();
        properties.setMaxRetryCount(maxRetryCount);
        return properties;
    }

    private ModerationTaskMessage moderationTask(int retryCount) {
        return ModerationTaskMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("MODERATION_REQUESTED")
                .occurredAt(LocalDateTime.now())
                .targetType(ModerationTargetType.CONTENT)
                .targetId(10L)
                .publisherUserId(1L)
                .title("测试内容")
                .content("测试正文")
                .retryCount(retryCount)
                .build();
    }

    private NotificationEventMessage notificationMessage() {
        return NotificationEventMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("NOTIFICATION_REQUESTED")
                .recipientUserId(1L)
                .actorUserId(2L)
                .type("LIKE_CONTENT")
                .content("用户点赞了你的内容")
                .retryCount(0)
                .build();
    }

    private HotScoreMessage hotScoreMessage() {
        return HotScoreMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("HOT_SCORE_RECALCULATE")
                .contentId(10L)
                .triggerType("LIKE")
                .retryCount(0)
                .build();
    }

    private Message delivery(long deliveryTag) {
        return MessageBuilder.withBody(new byte[0])
                .setDeliveryTag(deliveryTag)
                .build();
    }
}
