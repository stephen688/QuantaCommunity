package com.quanta.demo0.comment.service.bot;

import java.util.Locale;

/**
 * bot mention 判定（C-4 契约）。
 * 直接回复 bot 评论优先归类为 replied；否则按评论文本中的 @昵称
 * 判断 mentioned。改名过渡期仍识别系统名 QuantaBot，但不会改变对外系统标识。
 *
 * ============================================================
 * 【为什么做成无状态的静态工具类？】
 * ============================================================
 * 判定规则只依赖四个入参（文本、昵称、被回复人、bot ID），不查库不碰配置——
 * 配置（quantabot.bot-nickname=框框 / bot-user-id=10000，application.yml）由调用方
 * CommentAuditServiceImpl 从 QuantabotProperties 注入后传进来。
 * **纯函数意味着好测、好复用，也不会因为 Spring 代理 / 依赖缺失而在审核链路上炸掉。**
 * 唯一调用点在审核通过事务（maybeCreateBotMentionEvent），判定结果决定是否写
 * BOT_MENTION_REQUESTED Outbox——判多一次 bot 就多回一帖，判漏则用户 @ 了没反应。
 */
public final class BotMentionDetector {

    /** 工具类禁实例化：只允许 BotMentionDetector.isBotMentioned(...) 这种调用形式。 */
    private BotMentionDetector() {
    }

    /**
     * 判断评论是否命中 bot。
     *
     * 【判定顺序即优先级】
     * 1. replyUserId == botUserId（bot 账号 10000）→ 直接回复 bot，最强信号，优先归类 replied；
     * 2. 否则看文本：包含 "@框框"（botNickname，忽略大小写）或 "@quantabot"（旧系统名过渡）。
     * 【坑】两个开关项分别兜底：replyUserId 是结构化数据、不可靠时靠文本；
     * 文本匹配用 contains 而非全词匹配——"@框框 你好" 命中，但也意味着
     * 正文里恰好转述 "@框框" 一样会触发，属于有意接受的宽松策略。
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

        // 固定 Locale.ROOT：大小写折叠不受系统默认语言影响（例如土耳其语环境的 i 规则）。
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerNickname = botNickname.toLowerCase(Locale.ROOT);
        return lowerContent.contains("@" + lowerNickname)
                // 改名过渡期人工手打系统名仍可触发。
                || lowerContent.contains("@quantabot");
    }

    /**
     * 返回触发类型；直接回复 bot 优先归类为 replied。
     *
     * 【与 isBotMentioned 的分工】本方法只分类不判定——调用方必须先用
     * isBotMentioned 确认命中，再拿这个值填 BotMentionMessage.botTriggerKind；
     * QuantaBot 侧按 mentioned / replied 决定回答姿态（回应 @ 还是接续回复链）。
     */
    public static String triggerKind(Long replyUserId, Long botUserId) {
        if (replyUserId != null && replyUserId.equals(botUserId)) {
            return "replied";
        }
        return "mentioned";
    }
}
