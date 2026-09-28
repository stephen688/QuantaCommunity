package com.quanta.demo0.notification.mq.consumer;

import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationProducer;
import com.quanta.demo0.notification.service.NotificationConsumeService;
import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationConsumerReliabilityTest {

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
}
