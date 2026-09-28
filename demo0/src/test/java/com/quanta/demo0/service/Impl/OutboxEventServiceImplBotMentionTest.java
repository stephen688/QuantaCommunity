package com.quanta.demo0.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.platform.mq.entity.OutboxEvent;
import com.quanta.demo0.platform.mq.enums.OutboxEventStatus;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.platform.mq.mapper.OutboxEventMapper;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createBotMentionEvent 的事务性 Outbox 创建测试。
 */
class OutboxEventServiceImplBotMentionTest {

    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);
    private final OutboxEventServiceImpl service = new OutboxEventServiceImpl(
            outboxEventMapper,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            new OutboxDispatchProperties()
    );

    private ContentComment comment() {
        return ContentComment.builder()
                .commentId(100L)
                .contentId(10L)
                .answerId(null)
                .parentId(null)
                .replyCommentId(null)
                .replyUserId(null)
                .userId(3L)
                .content("@框框 学长好")
                .build();
    }

    @Test
    void 创建事件_类型与聚合信息正确() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        String eventId = service.createBotMentionEvent(comment(), List.of(), "mentioned");

        assertNotNull(eventId);
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();

        assertEquals(OutboxEventType.BOT_MENTION_REQUESTED.getCode(), event.getEventType());
        assertEquals("COMMENT", event.getAggregateType());
        assertEquals(100L, event.getAggregateId());
        assertEquals(OutboxEventStatus.PENDING.getCode(), event.getStatus());
        assertTrue(event.getPayload().contains("\"mentionedBot\":true"));
        assertTrue(event.getPayload().contains("\"commentImages\":[]"));
        assertTrue(event.getPayload().contains("\"botTriggerKind\":\"mentioned\""));
    }

    @Test
    void 图片列表null时消息仍带空数组() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        service.createBotMentionEvent(comment(), null, "replied");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        assertTrue(captor.getValue().getPayload().contains("\"commentImages\":[]"));
    }

    @Test
    void 插入失败抛异常_由调用方事务回滚() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(0);

        assertThrows(RuntimeException.class,
                () -> service.createBotMentionEvent(comment(), List.of(), "mentioned"));
    }

    @Test
    void 非法触发类型不写入毒丸事件() {
        assertThrows(CommentFailedException.class,
                () -> service.createBotMentionEvent(comment(), List.of(), "MENTIONED"));

        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }
}
