package com.quanta.demo0.content.mq.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.platform.mq.mapper.OutboxEventMapper;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.platform.mq.service.impl.OutboxEventServiceImpl;
import com.quanta.demo0.content.mq.producer.ContentEventProducer;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 相同帖子重复登记必须使用同一持久任务键，不因回填游标重跑产生新的付费任务。 */
class OutboxTopicRegistrationTest {
    @Test void sameContentUsesTheSameTaskKey() {
        var mapper = mock(OutboxEventMapper.class);
        OutboxEventService outboxEventService = new OutboxEventServiceImpl(mapper, new OutboxDispatchProperties());
        var appender = new OutboxEventAppender(
                outboxEventService, new ObjectMapper().registerModule(new JavaTimeModule()));
        var service = new ContentEventProducer(appender, new FeedEventProducer(appender));
        assertThat(service.createContentTopicTagEvent(9001L)).isEqualTo(service.createContentTopicTagEvent(9001L));
        verify(mapper,times(2)).insertIfAbsent(any());
        verify(mapper,never()).insert(any());
    }
}
