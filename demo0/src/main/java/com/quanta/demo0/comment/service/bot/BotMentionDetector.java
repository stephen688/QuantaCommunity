package com.quanta.demo0.comment.service.bot;

import java.util.Locale;

/**
 * bot mention 判定（C-4 契约）。
 * 直接回复 bot 评论优先归类为 replied；否则按评论文本中的 @昵称
 * 判断 mentioned。改名过渡期仍识别系统名 QuantaBot，但不会改变对外系统标识。
 */
public final class BotMentionDetector {

    private BotMentionDetector() {
    }

    /**
     * 判断评论是否命中 bot。
     *
     * @param content 评论全文
     * @param botNickname bot 对外昵称
     * @param replyUserId 被回复用户 ID，可空
     * @param botUserId bot 系统账号 ID
     */
    public static boolean isBotMentioned(
            String content,
            String botNickname,
            Long replyUserId,
            Long botUserId
    ) {
        // 直接回复 bot 是最强信号，优先于文本触发。
        if (replyUserId != null && replyUserId.equals(botUserId)) {
            return true;
        }
        if (content == null || botNickname == null || botNickname.isBlank()) {
            return false;
        }

        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerNickname = botNickname.toLowerCase(Locale.ROOT);
        return lowerContent.contains("@" + lowerNickname)
                // 改名过渡期人工手打系统名仍可触发。
                || lowerContent.contains("@quantabot");
    }

    /**
     * 返回触发类型；直接回复 bot 优先归类为 replied。
     */
    public static String triggerKind(Long replyUserId, Long botUserId) {
        if (replyUserId != null && replyUserId.equals(botUserId)) {
            return "replied";
        }
        return "mentioned";
    }
}
