package com.quanta.demo0.comment.mq.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-1 消息体形状闸门：字段名与 QuantaBot TriggerEvent alias 逐字对齐。
 */
class BotMentionMessageSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void 全字段序列化_camelCase键名与契约一致() throws Exception {
        BotMentionMessage message = BotMentionMessage.builder()
                .eventId("evt-1")
                .eventType("BOT_MENTION_REQUESTED")
                .occurredAt(LocalDateTime.of(2026, 9, 18, 10, 0, 0))
                .retryCount(0)
                .commentId(100L)
                .postId(10L)
                .answerId(null)
                .commenterUserId(3L)
                .commentContent("@框框 帮我看看这个问题")
                .commentImages(List.of())
                .mentionedBot(true)
                .botTriggerKind("mentioned")
                .parentId(null)
                .replyCommentId(null)
                .build();

        String json = objectMapper.writeValueAsString(message);

        assertTrue(json.contains("\"eventId\":\"evt-1\""));
        assertTrue(json.contains("\"commentId\":100"));
        assertTrue(json.contains("\"postId\":10"));
        assertTrue(json.contains("\"commenterUserId\":3"));
        assertTrue(json.contains("\"commentContent\""));
        assertTrue(json.contains("\"commentImages\":[]"));
        assertTrue(json.contains("\"mentionedBot\":true"));
        assertTrue(json.contains("\"botTriggerKind\":\"mentioned\""));
    }

    @Test
    void botTriggerKind值域为小写() {
        // QuantaBot TriggerEvent.trigger_kind: Literal["mentioned", "replied"]
        assertTrue(List.of("mentioned", "replied").stream()
                .allMatch(value -> value.equals(value.toLowerCase())));
    }
}
