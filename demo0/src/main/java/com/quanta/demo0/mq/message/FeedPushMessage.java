package com.quanta.demo0.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Feed 流推送消息实体类
 * 作用：定义消息的数据结构，生产者和消费者通过这个类传递数据
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FeedPushMessage implements Serializable {

    /**
     * 内容 ID（帖子 ID）
     * 作用：消费者根据这个 ID 查询帖子详情
     */
    private Long contentId;

    /**
     * 发布者用户 ID
     * 作用：消费者需要知道是谁发布的帖子，用于推送给粉丝
     */
    private Long publishUserId;

    /**
     * 内容类型：1-生活求助 2-专业问答
     * 作用：消费者根据类型推送到不同的 Feed 流（生活区/专业区）
     */
    private Integer contentType;

    /**
     * 发布时间戳（毫秒）
     * 作用：用于 Feed 流排序（按时间倒序）
     */
    private Long createTime;

    /**
     * 消息创建时间
     * 作用：记录消息发送时间，方便调试和监控
     */
    private LocalDateTime messageTime;

    /**
     * Outbox 事件 ID，同一事件重试时保持不变。
     */
    private String eventId;

    /**
     * 当前固定为 FEED_UPSERT_REQUESTED。
     */
    private String eventType;

    /**
     * Feed 事件发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 消费失败重试次数。
     */
    private Integer retryCount;
}