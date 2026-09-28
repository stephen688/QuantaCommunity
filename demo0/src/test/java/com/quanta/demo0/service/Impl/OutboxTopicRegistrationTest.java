package com.quanta.demo0.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.platform.mq.mapper.OutboxEventMapper;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 相同帖子重复登记必须使用同一持久任务键，不因回填游标重跑产生新的付费任务。 */
class OutboxTopicRegistrationTest {
    @Test void sameContentUsesTheSameTaskKey() {
        var mapper = mock(OutboxEventMapper.class);
        var service = new OutboxEventServiceImpl(mapper,new ObjectMapper().registerModule(new JavaTimeModule()),new OutboxDispatchProperties());
        assertThat(service.createContentTopicTagEvent(9001L)).isEqualTo(service.createContentTopicTagEvent(9001L));
        verify(mapper,times(2)).insertIfAbsent(any());
        verify(mapper,never()).insert(any());
    }
}
