package com.quanta.demo0.feed.mq.consumer;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;


import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.feed.mq.message.ProfileReconcileMessage;
import com.quanta.demo0.feed.mq.producer.ProfileReconcileProducer;
import com.quanta.demo0.feed.service.ExplicitPreferenceService;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 租约或可靠转发失败时，原消息必须保留，不能假 ACK。 */
class ProfileReconcileConsumerTest {
    @Test void nullPoisonMessageIsRejectedToBrokerDeadLetterWithoutRequeue() throws Exception {
        var inbox = mock(InboxEventService.class);
        var preferences = mock(ExplicitPreferenceService.class);
        var producer = mock(ProfileReconcileProducer.class);
        var channel = mock(Channel.class);
        var properties = new MessageProperties(); properties.setDeliveryTag(10L);
        new ProfileReconcileConsumer(inbox,preferences,producer).handle(null,new Message(new byte[0],properties),channel);
        verify(channel).basicNack(10L,false,false);
        verifyNoInteractions(inbox,preferences,producer);
    }
    @Test void lostSuccessLeaseDoesNotAck() throws Exception { verifyFailure(true); }
    @Test void unconfirmedRetryDoesNotAck() throws Exception { verifyFailure(false); }

    private void verifyFailure(boolean leaseLost) throws Exception {
        var inbox = mock(InboxEventService.class);
        var preferences = mock(ExplicitPreferenceService.class);
        var producer = mock(ProfileReconcileProducer.class);
        var channel = mock(Channel.class);
        var event = new ProfileReconcileMessage(); event.setEventId("test-profile-event"); event.setUserId(123L);
        event.setEventType(com.quanta.demo0.platform.mq.enums.OutboxEventType.USER_PROFILE_UPDATED.getCode());
        var properties = new MessageProperties(); properties.setDeliveryTag(9L);
        when(inbox.acquire(anyString(),anyString(),eq(event))).thenReturn(InboxAcquireResult.ACQUIRED);
        if (leaseLost) {
            when(inbox.markSuccess(anyString(),anyString(),anyString())).thenReturn(false);
        } else {
            doThrow(new IllegalStateException("Redis unavailable")).when(preferences).reconcile(123L);
            when(inbox.markRetry(anyString(),anyString(),anyString(),anyInt(),any(),anyString())).thenReturn(true);
            when(producer.sendRetryTask(event)).thenReturn(false);
        }
        new ProfileReconcileConsumer(inbox,preferences,producer).handle(event,new Message(new byte[0],properties),channel);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
        verify(channel).basicNack(9L,false,true);
    }
}
