package com.quanta.demo0.platform.mq.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.answer.mq.producer.AnswerEventProducer;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.comment.mq.producer.CommentEventProducer;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mq.producer.ContentEventProducer;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.platform.mq.mapper.OutboxEventMapper;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import com.quanta.demo0.platform.mq.service.impl.OutboxEventServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Outbox 域边界异常兼容契约：业务 Producer 恢复旧的异常类型和消息，平台层不依赖业务异常。
 */
class OutboxExceptionCompatibilityTest {

    @Test
    void contentInsertFailureUsesLegacyContentExceptionAndMessage() {
        OutboxEventMapper mapper = mapperReturning(0);
        ContentEventProducer producer = new ContentEventProducer(appender(mapper),
                new FeedEventProducer(appender(mapper)));

        ContentFailedException exception = assertThrows(ContentFailedException.class,
                () -> producer.createContentModerationEvent(content(), List.of()));

        assertEquals("创建帖子审核任务失败", exception.getMessage());
    }

    @Test
    void answerInsertFailureUsesLegacyContentExceptionAndMessage() {
        OutboxEventMapper mapper = mapperReturning(0);
        AnswerFailedCall call = new AnswerFailedCall(new AnswerEventProducer(appender(mapper)));

        ContentFailedException exception = assertThrows(ContentFailedException.class,
                () -> call.invoke());

        assertEquals("创建回答审核任务失败", exception.getMessage());
    }

    @Test
    void feedInsertFailureUsesLegacyContentExceptionAndMessage() {
        OutboxEventMapper mapper = mapperReturning(0);
        FeedEventProducer producer = new FeedEventProducer(appender(mapper));

        ContentFailedException exception = assertThrows(ContentFailedException.class,
                () -> producer.createHotScoreRecalculateEvent(10L, "LIKE"));

        assertEquals("创建热度重新计算事件失败", exception.getMessage());
    }

    @Test
    void commentAndBotInsertFailuresUseLegacyCommentExceptionAndMessages() {
        OutboxEventMapper mapper = mapperReturning(0);
        CommentEventProducer producer = new CommentEventProducer(appender(mapper));

        CommentFailedException moderationException = assertThrows(CommentFailedException.class,
                () -> producer.createCommentModerationEvent(comment(), List.of()));
        assertEquals("创建评论审核任务失败", moderationException.getMessage());

        CommentFailedException botException = assertThrows(CommentFailedException.class,
                () -> producer.createBotMentionEvent(comment(), List.of(), "mentioned"));
        assertEquals("创建 bot 触发事件失败", botException.getMessage());
    }

    @Test
    void payloadLimitUsesLegacyContentExceptionAndMessage() {
        OutboxEventMapper mapper = mapperReturning(1);
        ContentEventProducer producer = new ContentEventProducer(appender(mapper),
                new FeedEventProducer(appender(mapper)));
        Content oversized = Content.builder()
                .contentId(10L)
                .contentType(1)
                .publishUserId(3L)
                .title("title")
                .content("x".repeat(33_000))
                .build();

        ContentFailedException exception = assertThrows(ContentFailedException.class,
                () -> producer.createContentModerationEvent(oversized, List.of()));

        assertEquals("审核任务超过 32 KB，请缩短内容或图片地址", exception.getMessage());
    }

    @Test
    void serializationFailureRemainsLegacySystemException() throws JsonProcessingException {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        JsonProcessingException cause = new JsonProcessingException("boom") {
        };
        when(objectMapper.writeValueAsString(any())).thenThrow(cause);
        OutboxEventAppender appender = new OutboxEventAppender(mock(OutboxEventService.class), objectMapper);
        AnswerEventProducer producer = new AnswerEventProducer(appender);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.createAnswerModerationEvent(answer()));

        assertEquals("Outbox 事件序列化失败", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void notificationInsertFailureKeepsLegacySystemException() {
        OutboxEventMapper mapper = mapperReturning(0);
        NotificationEventProducer producer = new NotificationEventProducer(appender(mapper));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.createNotificationEvent(notification(), "CONTENT", 10L));

        assertEquals("创建通知 Outbox 事件失败", exception.getMessage());
    }

    @Test
    void platformMetadataFailureRemainsPlatformIllegalArgumentException() {
        OutboxEventAppender appender = appender(mapperReturning(1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> appender.append("event-1", "TYPE", null, 10L, Map.of()));

        assertEquals("Outbox 事件缺少必要元数据", exception.getMessage());
    }

    @Test
    void databaseExceptionIsNotTranslatedByProducerBoundary() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        IllegalStateException databaseFailure = new IllegalStateException("数据库连接失败");
        when(mapper.insert(any())).thenThrow(databaseFailure);
        AnswerEventProducer producer = new AnswerEventProducer(appender(mapper));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> producer.createAnswerModerationEvent(answer()));

        assertSame(databaseFailure, exception);
    }

    private static OutboxEventMapper mapperReturning(int result) {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        when(mapper.insert(any())).thenReturn(result);
        return mapper;
    }

    private static OutboxEventAppender appender(OutboxEventMapper mapper) {
        OutboxEventService service = new OutboxEventServiceImpl(mapper, new OutboxDispatchProperties());
        return new OutboxEventAppender(service, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private static Content content() {
        return Content.builder()
                .contentId(10L)
                .contentType(1)
                .publishUserId(3L)
                .title("title")
                .content("content")
                .build();
    }

    private static QuestionAnswer answer() {
        return QuestionAnswer.builder()
                .answerId(20L)
                .userId(3L)
                .content("answer")
                .build();
    }

    private static ContentComment comment() {
        return ContentComment.builder()
                .commentId(30L)
                .contentId(10L)
                .userId(3L)
                .content("comment")
                .build();
    }

    private static NotificationEventMessage notification() {
        return NotificationEventMessage.builder()
                .recipientUserId(3L)
                .type("LIKE_CONTENT")
                .content("liked")
                .build();
    }

    private record AnswerFailedCall(AnswerEventProducer producer) {
        private String invoke() {
            return producer.createAnswerModerationEvent(answer());
        }
    }
}
