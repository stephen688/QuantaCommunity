package com.quanta.demo0.feed.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.feed.dto.BotProfileEventDTO;
import com.quanta.demo0.feed.entity.UserProfileSignal;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.feed.mapper.UserProfileSignalMapper;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 显式偏好事实服务：拒绝未知标签、同事件重投不新增事实、冲突事件不得假成功。 */
class ExplicitPreferenceServiceImplTest {
    private final UserProfileSignalMapper mapper = mock(UserProfileSignalMapper.class);
    private final OutboxEventService outbox = mock(OutboxEventService.class);
    private final ExplicitPreferenceServiceImpl service = new ExplicitPreferenceServiceImpl(
            mapper, outbox, new ObjectMapper(), mock(StringRedisTemplate.class), new RecommendProperties());

    @Test
    void duplicateEventReturnsIdempotentResultWithoutAnotherWrite() {
        BotProfileEventDTO request = event();
        when(mapper.lockUser(123L)).thenReturn(0);
        when(mapper.insert(any())).thenReturn(1);
        assertThat(service.accept(request)).isTrue();
        var capture = org.mockito.ArgumentCaptor.forClass(UserProfileSignal.class);
        verify(mapper).insert(capture.capture());
        UserProfileSignal persisted = capture.getValue();
        assertThat(persisted.getTopics()).isEqualTo("[\"basketball\"]");
        assertThat(persisted.getUserId()).isEqualTo(123L);
        when(mapper.findEvent(request.getEventId())).thenReturn(persisted);
        assertThat(service.accept(request)).isFalse();
        verify(mapper, times(1)).insert(any());
        verify(outbox, times(1)).createProfileUpdatedEvent(123L, request.getEventId());
    }

    @Test
    void unknownTopicCannotBecomeAProfileFact() {
        BotProfileEventDTO request = event();
        request.setTopics(List.of("invented-interest"));
        assertThatThrownBy(() -> service.accept(request)).isInstanceOf(ContentFailedException.class);
        verifyNoInteractions(mapper, outbox);
    }

    @Test
    void reusedEventIdWithChangedPreferenceIsRejected() {
        BotProfileEventDTO request = event();
        when(mapper.findEvent(request.getEventId())).thenReturn(UserProfileSignal.builder()
                .eventId(request.getEventId()).userId(123L).memoryId(request.getMemoryId())
                .personaVersion("v1").revision(100L).operation("UPSERT")
                .topics("[\"basketball\"]").valence("negative").build());
        assertThatThrownBy(() -> service.accept(request)).isInstanceOf(ContentFailedException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void bannedTargetCannotReceiveNewInterestFacts() {
        when(mapper.lockUser(123L)).thenReturn(1);
        assertThatThrownBy(() -> service.accept(event())).isInstanceOf(ContentFailedException.class);
        verify(mapper, never()).insert(any());
        verifyNoInteractions(outbox);
    }

    static BotProfileEventDTO event() {
        BotProfileEventDTO request = new BotProfileEventDTO();
        request.setEventId("b09ac1bd-f5f3-43e0-a93b-2f2606daf0a8");
        request.setUserId(123L);
        request.setMemoryId("6110e1d664174ad8a67a73cb49b28edc");
        request.setPersonaVersion("v1");
        request.setRevision(100L);
        request.setOperation("UPSERT");
        request.setTopics(List.of("basketball"));
        request.setValence("positive");
        return request;
    }
}
