package com.quanta.demo0.search.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ES 索引校准消息（对账指令）。
 *
 * <p>贯穿"Outbox 落库 → OutboxDispatcher 首投 → 主队列消费 → 失败重试 → 死信"
 * 全生命周期的载体，字段保持最小集合：只告诉消费者"对谁对账"，
 * 不携带"对成什么样"——目标状态永远由消费时重新查 MySQL 得出。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchReconcileMessage implements Serializable {

    /**
     * Outbox 事件唯一 ID。
     * Inbox 使用这个字段判断同一事件是否已经处理成功。
     */
    private String eventId;

    /**
     * 固定为 SEARCH_RECONCILE_REQUESTED。
     */
    private String eventType;

    /**
     * 事件发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 需要校准的业务对象类型。
     * 当前支持 CONTENT 和 ANSWER。
     */
    private String targetType;

    /**
     * 帖子 ID 或回答 ID。
     */
    private Long targetId;

    /**
     * 触发原因，只用于日志和后台排查。
     * 例如 AUDIT_APPROVED、AUDIT_REJECTED、DELETE、LIKE、COLLECT、COMMENT_CHANGE。
     */
    private String triggerType;

    /**
     * 消费失败次数。
     */
    private Integer retryCount;
}