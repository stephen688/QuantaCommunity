package com.quanta.demo0.feed.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户行为消息模型（画像更新信号，推荐流个性化 D2）。
 * 职责：承载"用户对帖子的一次行为"事件，由赞/藏/评 Outbox 与浏览对账任务发出，
 * 画像消费者消费后累加 Redis 画像 Hash。
 * 边界：消费者不信任消息内的计数，只按 userId + contentId 重新校验帖子状态后累加。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserBehaviorMessage implements Serializable {

    /**
     * 行为人（画像主体）。
     * 画像 Hash 按 userId 维度组织。
     */
    private Long userId;

    /**
     * 交互的帖子 ID。
     * 消费者用它重新查询 MySQL 帖子当前状态，已删/驳回帖不累加画像。
     */
    private Long contentId;

    /**
     * 行为类型，只允许 LIKE / COLLECT / COMMENT / VIEW。
     * 权重换算由画像消费侧承担，消息本身不携带权重。
     */
    private String behaviorType;

    /**
     * Outbox 事件唯一 ID。
     * 消费者依靠它判断消息是否重复（Inbox 幂等键）。
     */
    private String eventId;

    /**
     * 可靠事件类型，固定为 USER_BEHAVIOR_REQUESTED。
     */
    private String eventType;

    /**
     * 事件发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 消费失败重试次数。
     */
    private Integer retryCount;
}
