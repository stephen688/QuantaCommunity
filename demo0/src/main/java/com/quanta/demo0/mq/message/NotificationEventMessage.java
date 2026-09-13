package com.quanta.demo0.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知推送消息实体类
 * 作用：定义通知消息的数据结构，生产者和消费者通过这个类传递数据
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationEventMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 接收通知的用户 ID（收件人）
     * 作用：消费者根据这个 ID 确定通知推送给谁
     */
    private Long recipientUserId;

    /**
     * 触发通知的用户 ID（触发者，系统通知可为空）
     * 作用：前端显示"xxx 评论了你的内容"中的 xxx
     */
    private Long actorUserId;

    /**
     * 通知类型（如 COMMENT_ON_CONTENT、LIKE_CONTENT 等）
     * 作用：消费者根据类型决定通知内容和前端跳转逻辑
     */
    private String type;

    /**
     * 通知摘要内容
     * 作用：前端直接显示的通知文本
     */
    private String content;

    /**
     * 扩展字段（关联 ID、审核结果等）
     * 作用：存储 contentId、commentId、answerId、auditResult、rejectReason 等
     * 前端根据这些字段跳转到对应页面
     */
    private Map<String, Object> payload;

    /**
     * 消息创建时间
     * 作用：记录消息发送时间，方便调试和监控
     */
    private LocalDateTime createdAt;


    /**
     * Outbox 生成的事件唯一 ID。
     */
    private String eventId;

    /**
     * 当前固定为 NOTIFICATION_REQUESTED。
     */
    private String eventType;

    /**
     * 通知事件真正发生的时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 通知消费者重试次数。
     */
    private Integer retryCount;
}