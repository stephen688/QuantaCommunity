package com.quanta.demo0.enums;

import lombok.Getter;

/**
 * 通知类型枚举
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