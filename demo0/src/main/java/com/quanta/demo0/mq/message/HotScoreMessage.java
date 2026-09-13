package com.quanta.demo0.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HotScoreMessage implements Serializable {

    /**
     * Outbox 事件唯一 ID。
     * 消费者依靠它判断消息是否重复。
     */
    private String eventId;

    /**
     * 可靠事件类型，固定为 HOT_SCORE_RECALCULATE_REQUESTED。
     */
    private String eventType;

    /**
     * 事件发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 需要重新计算热度的帖子 ID。
     * 消费者不会相信消息里的计数，而是用这个 ID 重新查询 MySQL。
     */
    private Long contentId;

    /**
     * 触发原因，只用于日志和排查。
     * 可能是 LIKE、UNLIKE、COLLECT、UNCOLLECT、COMMENT_ADD、COMMENT_DELETE。
     */
    private String triggerType;

    /**
     * 消费失败重试次数。
     */
    private Integer retryCount;

    /**
     * 暂时保留旧字段，兼容 RabbitMQ 中可能遗留的旧消息。
     */
    private LocalDateTime eventTime;
}