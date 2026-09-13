package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对应数据库表：tb_outbox_event
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 数据库自增主键。
     */
    private Long id;

    /**
     * 业务事件唯一 ID。

     * 后面发送 RabbitMQ 时也使用这个 ID，
     * Inbox 会根据它判断消息是否重复。
     */
    private String eventId;

    /**
     * 事件类型，例如 MODERATION_REQUESTED。
     */
    private String eventType;

    /**
     * 业务对象类型，例如 CONTENT。
     */
    private String aggregateType;

    /**
     * 对应帖子 ID。
     */
    private Long aggregateId;

    /**
     * 需要发送给审核消费者的 JSON。
     */
    private String payload;

    /**
     * PENDING / PROCESSING / SENT / DEAD。
     */
    private String status;

    /**
     * 重试次数。
     */
    private Integer retryCount;

    /**
     * 下一次重试时间。
     */
    private LocalDateTime nextRetryTime;

    /**
     * 锁定实例 ID。
     */
    private String lockedBy;

    /**
     * 锁定时间。
     */
    private LocalDateTime lockedUntil;

    /**
     * 最后一次重试错误信息。
     */
    private String lastError;

    /**
     * 重试次数。
     */
    private Integer replayCount;

    /**
     * 最后一次重试实例 ID。
     */
    private Long lastReplayBy;

    /**
     * 最后一次重试时间。
     */
    private LocalDateTime lastReplayTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime sentTime;
}