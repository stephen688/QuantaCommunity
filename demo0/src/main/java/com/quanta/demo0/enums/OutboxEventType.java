package com.quanta.demo0.enums;

import lombok.Getter;

@Getter
public enum OutboxEventType {

    /**
     * 请求审核帖子。
     */
    MODERATION_REQUESTED(
            "MODERATION_REQUESTED"
    ),

    /**
     * 请求生成通知。
     */
    NOTIFICATION_REQUESTED(
            "NOTIFICATION_REQUESTED"
    ),

    /**
     * 根据 MySQL 当前状态，将帖子加入或校准粉丝 Feed。
     */
    FEED_UPSERT_REQUESTED("FEED_UPSERT_REQUESTED"),

    /**
     * 根据 MySQL 当前状态，删除或校准粉丝 Feed。
     */
    FEED_DELETE_REQUESTED("FEED_DELETE_REQUESTED"),

    /**
     * 根据 MySQL 当前点赞、收藏、评论数据，重新计算帖子热度。
     */
    HOT_SCORE_RECALCULATE_REQUESTED("HOT_SCORE_RECALCULATE_REQUESTED"),

    /**
     * 根据 MySQL 当前状态，重新校准 Elasticsearch 文档。
     */
    SEARCH_RECONCILE_REQUESTED("SEARCH_RECONCILE_REQUESTED"),

    /**
     * 审核通过的评论命中 bot（@ 或直接回复），请求 QuantaBot 触发处理（C-1）。
     */
    BOT_MENTION_REQUESTED("BOT_MENTION_REQUESTED"),

    /**
     * 用户对帖子发生一次行为（赞/藏/评/浏览），请求画像消费者累加画像（D2）。
     */
    USER_BEHAVIOR_REQUESTED("USER_BEHAVIOR_REQUESTED"),

    /** 审核通过后异步生成受控主题标签。 */
    CONTENT_TOPIC_TAG_REQUESTED("CONTENT_TOPIC_TAG_REQUESTED"),

    /** 主服务已持久化显式偏好，通知消费者重建用户当前画像。 */
    USER_PROFILE_UPDATED("user.profile.updated");



    private final String code;

    OutboxEventType(String code) {
        this.code = code;
    }
}
