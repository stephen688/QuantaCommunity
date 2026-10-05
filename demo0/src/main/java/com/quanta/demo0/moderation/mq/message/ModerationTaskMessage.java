// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/mq/message/ModerationTaskMessage.java
package com.quanta.demo0.moderation.mq.message;

import com.quanta.demo0.moderation.enums.ModerationTargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核任务消息：一条待审核内容（帖子/回答/评论）在 MQ 上的载体。
 *
 * ============================================================
 * 【为什么消息里带全量内容，而不只带 targetId？】
 * ============================================================
 * 消费端拿到消息后可以直接送审，不必回查业务库：
 * - 好处：审核域与业务表解耦，标题/正文/图片在**发布那一刻**就固化进消息，
 *   之后业务侧的并发修改不会让审核内容"变脸"；
 * - 代价：消息体变大。属于典型的"读优化"消息设计。
 *
 * 两条投递路径：
 * 首发Outbox 事件（MODERATION_REQUESTED）→ OutboxRouteRegistry → moderation.exchange；
 * 重试ModerationProducer → moderation.retry.exchange（延迟 60 秒回主队列）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationTaskMessage implements Serializable {
    private static final long serialVersionUID = 1L;


    /**
     * Outbox 生成的唯一事件 ID。
     *
     * 同一事件即使被发送两次，eventId 也保持不变。
     */
    private String eventId;

    /**
     * 当前固定为 MODERATION_REQUESTED。
     */
    private String eventType;

    /**
     * 业务事件真正发生的时间。
     */
    private LocalDateTime occurredAt;


    private ModerationTargetType targetType; // CONTENT / ANSWER / COMMENT
    private Long targetId;                   // 帖子/回答/评论 ID
    private Long publisherUserId;            // 发布者 ID
    private String title;                    // 标题（仅帖子）
    private String content;                  // 正文
    private List<String> imageUrls;          // 图片 URL 列表
    // 消息构造时间（区别于 occurredAt 的事件发生时间）
    private LocalDateTime createdAt;
    private Integer retryCount;              // 重试次数

    // 【坑】retryCount 随消息走：工作流失败时 +1 后重发，达到 max-retry-count（=3，
    // 见 quanta.moderation.max-retry-count）就标记 DEAD，见 ModerationWorkflowServiceImpl
}