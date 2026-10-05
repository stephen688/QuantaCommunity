package com.quanta.demo0.notification.enums;

import lombok.Getter;

/**
 * 通知类型枚举
 *
 * code 是整条链路的"方言"：生产方按业务场景选类型并拼 content，落库存 code
 * 字符串，读侧/推送侧用 desc 翻译，前端凭 code + payload 决定跳转。
 * tb_notification.type 是普通字符串列，新增通知类型只需加枚举值和对应
 * 生产方调用，表结构和消费者逻辑都不用动。
 *
 * ============================================================
 * 【文案放枚举 desc，而不是前端按 code 自己翻译？】
 * ============================================================
 * desc 服务两处服务端出口：列表 VO（NotificationServiceImpl.getTypeDesc）和
 * WebSocket 推送 VO（NotificationConsumer），**保证两个出口的文案口径一致**，
 * 且历史通知不受前端文案改动影响；跳转等交互逻辑仍只依赖 code，code 一旦
 * 落库就是永久事实，只增不改。
 */
@Getter
public enum NotificationType {

    // 互动类（评论回答，评论内容，点赞内容，点赞评论，点赞回答，回复评论，关注用户）
    COMMENT_ON_CONTENT("COMMENT_ON_CONTENT", "评论了你的内容"),
    COMMENT_ON_ANSWER("COMMENT_ON_ANSWER", "评论了你的回答"),
    COMMENT_REPLY("COMMENT_REPLY", "回复了你的评论"),
    LIKE_CONTENT("LIKE_CONTENT", "点赞了你的内容"),
    LIKE_COMMENT("LIKE_COMMENT", "点赞了你的评论"),
    LIKE_ANSWER("LIKE_ANSWER", "点赞了你的回答"),
    USER_FOLLOW("USER_FOLLOW", "关注了你"),

    // 审核/举报类（内容审核结果，回答审核结果，帖子举报处理结果，评论举报处理结果）
    CONTENT_AUDIT_RESULT("CONTENT_AUDIT_RESULT", "内容审核结果"),
    ANSWER_AUDIT_RESULT("ANSWER_AUDIT_RESULT", "回答审核结果"),
    CONTENT_REPORT_RESULT("CONTENT_REPORT_RESULT", "帖子举报处理结果"),
    COMMENT_REPORT_RESULT("COMMENT_REPORT_RESULT", "评论举报处理结果"),

    // 问答/认证类（回答采纳采纳，身份认证结果）
    ANSWER_ACCEPTED("ANSWER_ACCEPTED", "你的回答被采纳"),
    IDENTITY_AUDIT_RESULT("IDENTITY_AUDIT_RESULT", "身份认证结果");

    private final String code;
    private final String desc;

    NotificationType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    /**
     * 根据 code 获取枚举
     *
     * 【找不到返回 null，不抛异常】调用方必须判空：消费者推送时用
     * typeEnum != null 降级成空串 typeDesc；对比 NotificationServiceImpl 里
     * 用 valueOf + catch 的写法（返回"未知通知"），两处是同一问题的两种防御。
     */
    public static NotificationType getByCode(String code) {
        for (NotificationType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}