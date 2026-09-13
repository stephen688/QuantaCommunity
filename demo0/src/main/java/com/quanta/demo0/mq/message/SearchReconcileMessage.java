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