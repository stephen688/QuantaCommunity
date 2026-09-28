package com.quanta.demo0.comment.service.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-4 mention 判定：直接回复 bot 优先，文本 @昵称 兜底。
 */
class BotMentionDetectorTest {

    @Test
    void 回复bot评论_命中replied() {
        assertTrue(BotMentionDetector.isBotMentioned(
                "学长说得好", "框框", 10000L, 10000L));
        assertEquals("replied", BotMentionDetector.triggerKind(10000L, 10000L));
    }

    @Test
    void 文本含at昵称_命中mentioned() {
        assertTrue(BotMentionDetector.isBotMentioned(
                "@框框 帮我看看", "框框", null, 10000L));
        assertEquals("mentioned", BotMentionDetector.triggerKind(null, 10000L));
    }

    @Test
    void at昵称大小写不敏感_系统名也兜底() {
        assertTrue(BotMentionDetector.isBotMentioned(
                "@quantabot 在吗", "框框", null, 10000L));
    }

    @Test
    void 无mention不命中() {
        assertFalse(BotMentionDetector.isBotMentioned(
                "普通评论", "框框", null, 10000L));
        assertFalse(BotMentionDetector.isBotMentioned(
                "普通回复", "框框", 3L, 10000L));
    }

    @Test
    void 昵称是前缀的假命中_至少要求at加全名() {
        assertFalse(BotMentionDetector.isBotMentioned(
                "@框 你好", "框框", null, 10000L));
    }

    @Test
    void 回复与文本同时命中_replied优先() {
        assertEquals("replied",
                BotMentionDetector.triggerKind(10000L, 10000L));
    }
}
