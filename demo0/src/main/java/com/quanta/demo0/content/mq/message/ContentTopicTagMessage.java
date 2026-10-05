package com.quanta.demo0.content.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容主题标签可靠事件消息。
 *
 * 职责：只传递事件标识和帖子标识，消费者始终回读 MySQL 当前内容；
 * 边界：不携带正文，避免 Outbox/Rabbit payload 保存原文和过期内容。
 *
 * ============================================================
 * 【和 ModerationTaskMessage 对比着看：一个带值，一个带引用】
 * ============================================================
 * 审核消息（ModerationTaskMessage）携带标题/正文/图片 URL 的**原文**——
 * 审核对象是"发布那一刻的内容"，即使帖子随后被修改，也必须按提交时的文本判。
 * 打标消息只带 contentId——打标对象是"当前内容"，消费者回读 MySQL 拿最新状态，
 * 顺带绕开 Outbox payload 的 32KB 上限（见 OutboxEventAppender 的大小校验）。
 * **消息体带值还是带引用，取决于消费者要的是"历史快照"还是"当前事实"。**
 *
 * 【retryCount 为什么放在消息体里，而不是让 broker 记？】
 * 本链路的重试 = 消费失败后转发进 60s TTL 重试队列、到期死信回主交换机
 * （拓扑见 TopicTagMQConfig）。broker 只负责"延迟再投一次"，
 * 不知道也不该知道"这是第几次重试"——业务重试进度必须随消息流转
 * （消费失败 +1 后再转发），同时 Inbox 表同步记账（markRetry/markDead），
 * 超限判定（retryCount >= maxRetryCount）才有依据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentTopicTagMessage implements Serializable {

    /** 事件唯一标识。本链路是确定性 UUID（同帖必同值，见 ContentEventProducer.createContentTopicTagEvent），是 Inbox 幂等键与发布确认（correlation）的身份字段。 */
    private String eventId;

    /** 事件类型码。消费者用它校验消息确实属于本链路（防串队列），不匹配按毒消息直接进死信。 */
    private String eventType;

    /** 帖子 ID。内容走引用不走值——消费者拿 id 回读 MySQL（理由见类注释"带值 vs 带引用"）。 */
    private Long contentId;

    /** 事件登记时间（写入 Outbox 的时刻）。与消费时间对比可估算链路积压与延迟。 */
    private LocalDateTime occurredAt;

    /** 业务重试计数。首发为 0，消费失败 +1 后随消息转发重试队列；超过上限转死信。 */
    private Integer retryCount;
}
