package com.quanta.demo0.mq.consumer;

import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.mq.producer.ContentTopicTagProducer;
import com.quanta.demo0.content.properties.ContentTopicProperties;
import com.quanta.demo0.service.ContentTopicTagService;
import com.quanta.demo0.service.InboxEventService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentTopicTagConsumerTest {
    @Test
    void nullPoisonMessageIsRejectedToBrokerDeadLetterWithoutRequeue() throws Exception {
        var channel = mock(Channel.class);
        var inbox = mock(InboxEventService.class);
        var tags = mock(ContentTopicTagService.class);
        var producer = mock(ContentTopicTagProducer.class);
        var properties = new MessageProperties(); properties.setDeliveryTag(18L);
        new ContentTopicTagConsumer(inbox,tags,producer,new ContentTopicProperties())
                .handle(null,new Message(new byte[0],properties),channel);
        verify(channel).basicNack(18L,false,false);
        org.mockito.Mockito.verifyNoInteractions(inbox,tags,producer);
    }

    @Test
    void alreadySuccessfulInboxEventIsAckedWithoutCallingTheTaggerAgain() throws Exception {
        InboxEventService inboxEventService = mock(InboxEventService.class);
        ContentTopicTagService tagService = mock(ContentTopicTagService.class);
        ContentTopicTagProducer producer = mock(ContentTopicTagProducer.class);
        Channel channel = mock(Channel.class);
        when(inboxEventService.acquire(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(ContentTopicTagMessage.class)))
                .thenReturn(InboxAcquireResult.ALREADY_SUCCESS);

        ContentTopicTagConsumer consumer = new ContentTopicTagConsumer(
                inboxEventService,
                tagService,
                producer,
                new ContentTopicProperties());
        ContentTopicTagMessage message = ContentTopicTagMessage.builder()
                .eventId("topic-event-1")
                .eventType("CONTENT_TOPIC_TAG_REQUESTED")
                .contentId(7L)
                .occurredAt(LocalDateTime.now())
                .retryCount(0)
                .build();
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(17L);

        consumer.handle(message, new Message(new byte[0], properties), channel);

        verify(channel).basicAck(17L, false);
        verify(tagService, never()).tagContent(7L);
        verify(producer, never()).sendRetryTask(message);
    }
}
