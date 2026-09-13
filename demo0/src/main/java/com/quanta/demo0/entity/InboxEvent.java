package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对应数据库表：tb_inbox_event
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /**
     * Outbox 生成的事件唯一 ID。
     */
    private String eventId;

    /**
     * 消费者名称。
     */
    private String consumerName;

    /**
     * 对应的 Outbox 数据库主键。
     */
    private Long outboxEventId;

    private String eventType;

    private String aggregateType;

    private Long aggregateId;

    /**
     * PROCESSING / RETRYING / SUCCESS / DEAD。
     */
    private String status;

    private Integer retryCount;

    /**
     * 当前处理实例。
     */
    private String lockedBy;

    /**
     * 当前实例的处理权到什么时候过期。
     */
    private LocalDateTime lockedUntil;

    private String lastError;

    private Integer replayCount;

    private Long lastReplayBy;

    private LocalDateTime lastReplayTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private LocalDateTime processedTime;
}