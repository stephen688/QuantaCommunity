package com.quanta.demo0.platform.mq.consumer;

import com.quanta.demo0.feed.mq.consumer.FeedDeleteConsumer;
import com.quanta.demo0.feed.mq.consumer.FeedPushConsumer;
import com.quanta.demo0.feed.mq.consumer.HotScoreUpdateConsumer;
import com.quanta.demo0.moderation.enums.ModerationDecision;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.mq.consumer.ModerationConsumer;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.search.service.AnswerSearchService;
import com.quanta.demo0.search.service.ContentIndexService;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.feed.mq.message.FeedDeleteMessage;
import com.quanta.demo0.feed.mq.message.FeedPushMessage;
import com.quanta.demo0.feed.mq.message.HotScoreMessage;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.moderation.mq.producer.ModerationProducer;
import com.quanta.demo0.feed.mq.producer.FeedDeleteProducer;
import com.quanta.demo0.feed.mq.producer.FeedPushProducer;
import com.quanta.demo0.feed.mq.producer.HotScoreUpdateProducer;
import com.quanta.demo0.moderation.properties.AliyunModerationProperties;
import com.quanta.demo0.moderation.result.ModerationWorkflowResult;
import com.quanta.demo0.moderation.service.ContentModerationService;
import com.quanta.demo0.answer.service.AnswerAuditService;
import com.quanta.demo0.comment.service.CommentAuditService;
import com.quanta.demo0.content.service.ContentAuditService;
import com.quanta.demo0.feed.service.HotContentService;
import com.quanta.demo0.feed.service.FollowFeedService;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.quanta.demo0.moderation.service.ModerationWorkflowService;
import com.quanta.demo0.moderation.service.impl.ModerationWorkflowServiceImpl;
import com.quanta.demo0.search.service.impl.SearchReconcileServiceImpl;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConsumerReliabilityTests {

    @Test
    void moderationListenerOnlyDependsOnWorkflowForBusinessProcessing() {
        assertThat(Arrays.stream(ModerationConsumer.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName))
                .contains("com.quanta.demo0.moderation.service.ModerationWorkflowService")
                .doesNotContain(
                        ContentModerationService.class.getName(),
                        "com.quanta.demo0.moderation.service.ModerationResultService",
                        InboxEventService.class.getName(),
                        ModerationProducer.class.getName(),
                        AliyunModerationProperties.class.getName()
                );
    }

    @Test
    void duplicateModerationEventOnlyAcknowledgesWithoutRunningBusinessAgain() throws Exception {
        ModerationWorkflowService workflowService = mock(ModerationWorkflowService.class);
        Channel channel = mock(Channel.class);
        ModerationTaskMessage task = moderationTask(0);

        when(workflowService.process(task)).thenReturn(ModerationWorkflowResult.ACK);

        ModerationConsumer consumer = moderationConsumer(workflowService);

        consumer.handleModerationTask(task, channel, 1L);

        verify(channel).basicAck(1L, false);
        verify(workflowService).process(task);
    }

    @Test
    void exhaustedModerationRetryMarksInboxDeadAndRejectsToDlq() throws Exception {
        ModerationWorkflowService workflowService = mock(ModerationWorkflowService.class);
        Channel channel = mock(Channel.class);
        ModerationTaskMessage task = moderationTask(3);

        when(workflowService.process(task)).thenReturn(ModerationWorkflowResult.DEAD);

        ModerationConsumer consumer = moderationConsumer(workflowService);

        consumer.handleModerationTask(task, channel, 3L);

        verify(channel).basicNack(3L, false, false);
        verify(workflowService).process(task);
    }

    @Test
    void approvedTargetAndInboxSuccessShareWorkflowTransactionBoundary() throws Exception {
        ContentModerationService moderationService = mock(ContentModerationService.class);
        ContentAuditService contentAuditService = mock(ContentAuditService.class);
        AnswerAuditService answerAuditService = mock(AnswerAuditService.class);
        CommentAuditService commentAuditService = mock(CommentAuditService.class);
        InboxEventService inboxEventService = mock(InboxEventService.class);
        ModerationProducer moderationProducer = mock(ModerationProducer.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        AliyunModerationProperties properties = moderationProperties(3);
        ModerationTaskMessage task = moderationTask(0);
        ModerationResult passedResult = ModerationResult.builder()
                .decision(ModerationDecision.PASS)
                .build();

        when(inboxEventService.acquire(eq("moderation-consumer"), anyString(), eq(task)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(moderationService.moderate(task)).thenReturn(passedResult);
        when(inboxEventService.markSuccess(eq("moderation-consumer"), eq(task.getEventId()), anyString()))
                .thenReturn(true);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        ModerationWorkflowServiceImpl workflow = new ModerationWorkflowServiceImpl(
                moderationService,
                contentAuditService,
                answerAuditService,
                commentAuditService,
                inboxEventService,
                moderationProducer,
                properties,
                transactionManager
        );

        assertThat(workflow.process(task)).isEqualTo(ModerationWorkflowResult.ACK);

        InOrder order = inOrder(transactionManager, contentAuditService, inboxEventService);
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(contentAuditService).approveContent(task.getTargetId());
        order.verify(inboxEventService).markSuccess(
                eq("moderation-consumer"),
                eq(task.getEventId()),
                anyString()
        );
        order.verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void moderationWorkflowTreatsAlreadySuccessfulInboxEventAsIdempotentAck() {
        ContentModerationService moderationService = mock(ContentModerationService.class);
        ContentAuditService contentAuditService = mock(ContentAuditService.class);
        AnswerAuditService answerAuditService = mock(AnswerAuditService.class);
        CommentAuditService commentAuditService = mock(CommentAuditService.class);
        InboxEventService inboxEventService = mock(InboxEventService.class);
        ModerationProducer moderationProducer = mock(ModerationProducer.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ModerationTaskMessage task = moderationTask(0);

        when(inboxEventService.acquire(eq("moderation-consumer"), anyString(), eq(task)))
                .thenReturn(InboxAcquireResult.ALREADY_SUCCESS);

        ModerationWorkflowServiceImpl workflow = new ModerationWorkflowServiceImpl(
                moderationService,
                contentAuditService,
                answerAuditService,
                commentAuditService,
                inboxEventService,
                moderationProducer,
                moderationProperties(3),
                transactionManager
        );

        assertThat(workflow.process(task)).isEqualTo(ModerationWorkflowResult.ACK);
        verifyNoInteractions(moderationService, contentAuditService, answerAuditService,
                commentAuditService, moderationProducer, transactionManager);
    }

    @Test
    void moderationWorkflowMarksExhaustedRetryDeadAndSavesFailureRecord() {
        ContentModerationService moderationService = mock(ContentModerationService.class);
        ContentAuditService contentAuditService = mock(ContentAuditService.class);
        AnswerAuditService answerAuditService = mock(AnswerAuditService.class);
        CommentAuditService commentAuditService = mock(CommentAuditService.class);
        InboxEventService inboxEventService = mock(InboxEventService.class);
        ModerationProducer moderationProducer = mock(ModerationProducer.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ModerationTaskMessage task = moderationTask(3);
        ModerationResult failedResult = ModerationResult.builder()
                .decision(ModerationDecision.ERROR)
                .rejectReason("模拟审核服务异常")
                .build();

        when(inboxEventService.acquire(eq("moderation-consumer"), anyString(), eq(task)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(moderationService.moderate(task)).thenReturn(failedResult);
        when(inboxEventService.markDead(eq("moderation-consumer"), eq(task.getEventId()),
                anyString(), eq("模拟审核服务异常")))
                .thenReturn(true);

        ModerationWorkflowServiceImpl workflow = new ModerationWorkflowServiceImpl(
                moderationService,
                contentAuditService,
                answerAuditService,
                commentAuditService,
                inboxEventService,
                moderationProducer,
                moderationProperties(3),
                transactionManager
        );

        assertThat(workflow.process(task)).isEqualTo(ModerationWorkflowResult.DEAD);
        verify(moderationService).saveFailedRecord(task, failedResult);
        verify(inboxEventService).markDead(eq("moderation-consumer"), eq(task.getEventId()),
                anyString(), eq("模拟审核服务异常"));
        verifyNoInteractions(contentAuditService, answerAuditService, commentAuditService,
                moderationProducer, transactionManager);
    }

    @Test
    void outOfOrderFeedEventsAlwaysReconcileFromCurrentBusinessState() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        FollowFeedService followFeedService = mock(FollowFeedService.class);
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
        ReflectionTestUtils.setField(deleteConsumer, "followFeedService", followFeedService);
        ReflectionTestUtils.setField(deleteConsumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(deleteConsumer, "feedDeleteProducer", mock(FeedDeleteProducer.class));

        FeedPushConsumer pushConsumer = new FeedPushConsumer();
        ReflectionTestUtils.setField(pushConsumer, "followFeedService", followFeedService);
        ReflectionTestUtils.setField(pushConsumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(pushConsumer, "feedPushProducer", mock(FeedPushProducer.class));

        deleteConsumer.handleFeedDeleteMessage(deleteMessage, delivery(4L), channel);
        pushConsumer.handleFeedPushMessage(pushMessage, delivery(5L), channel);

        verify(followFeedService).reconcileContentFeed(10L, 1L, 2, null);
        verify(followFeedService).reconcileContentFeed(10L, 1L, 2, 123L);
        verify(channel).basicAck(4L, false);
        verify(channel).basicAck(5L, false);
    }

    @Test
    void repeatedHotScoreAndSearchEventsUseCurrentMySqlSnapshot() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        HotContentService hotContentService = mock(HotContentService.class);
        Channel channel = mock(Channel.class);
        HotScoreMessage first = hotScoreMessage();
        HotScoreMessage second = hotScoreMessage();

        when(inboxEventService.acquire(eq("hot-score-consumer"), anyString(), any(HotScoreMessage.class)))
                .thenReturn(InboxAcquireResult.ACQUIRED);
        when(inboxEventService.markSuccess(anyString(), anyString(), anyString())).thenReturn(true);

        HotScoreUpdateConsumer consumer = new HotScoreUpdateConsumer();
        ReflectionTestUtils.setField(consumer, "hotContentService", hotContentService);
        ReflectionTestUtils.setField(consumer, "inboxEventService", inboxEventService);
        ReflectionTestUtils.setField(consumer, "hotScoreUpdateProducer", mock(HotScoreUpdateProducer.class));

        consumer.handleHotScoreUpdate(first, delivery(6L), channel);
        consumer.handleHotScoreUpdate(second, delivery(7L), channel);

        verify(hotContentService, times(2)).reconcileHotScore(10L);

        ContentIndexService contentIndexService = mock(ContentIndexService.class);
        AnswerSearchService answerSearchService = mock(AnswerSearchService.class);
        SearchReconcileServiceImpl searchService = new SearchReconcileServiceImpl(contentIndexService, answerSearchService);
        searchService.reconcileSearchIndex(ModerationTargetType.CONTENT.name(), 10L);
        searchService.reconcileSearchIndex(ModerationTargetType.CONTENT.name(), 10L);

        verify(contentIndexService, times(2)).upsertByContentId(10L);
    }

    private ModerationConsumer moderationConsumer(
            ModerationWorkflowService workflowService
    ) {
        ModerationConsumer consumer = new ModerationConsumer();
        ReflectionTestUtils.setField(consumer, "workflowService", workflowService);
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
