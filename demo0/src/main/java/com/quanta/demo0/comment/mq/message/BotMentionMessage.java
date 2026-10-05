package com.quanta.demo0.comment.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * bot 触发事件消息（C-1 契约）。
 *
 * 消费方是 QuantaBot CommentEventConsumer；字段保持 camelCase，
 * 由对端 Pydantic alias 作为消息契约闸门。
 *
 * ============================================================
 * 【为什么消息里重复带了 eventType / eventId 这些"外层已有"的字段？】
 * ============================================================
 * Outbox 表本身有 event_id / event_type 列，看似冗余，但 payload 要跨进
 * Python 消费端，对端只能看到消息体——**契约字段必须自描述**：
 * eventType 让消费者一眼校验"这是不是我该处理的消息"，eventId 是它做
 * 幂等去重与日志追踪的主键，retryCount 由消费端维护用于判断是否放弃。
 * 改任何一个字段都是跨语言契约变更（同 ContentMQConfig 的队列名约束）。
 *
 * 序列化路径：写入时 ObjectMapper 序列化（OutboxEventAppender），投递前
 * OutboxRouteRegistry 再反序列化校验一次——@NoArgsConstructor 就是为 Jackson
 * 的两段 round-trip 准备的，删掉会导致事件永远发不出去。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotMentionMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Outbox 生成的事件唯一 ID。 */
    private String eventId;

    /** 固定为 BOT_MENTION_REQUESTED。 */
    private String eventType;

    /** 事件发生时间。 */
    private LocalDateTime occurredAt;

    /** 消费者重试次数，首发为 0。 */
    private Integer retryCount;

    /** 触发评论 ID。 */
    private Long commentId;

    /** 帖子 ID（= contentId）。 */
    private Long postId;

    /** 专业区回答 ID，可空。 */
    private Long answerId;

    /** 触发评论作者。 */
    private Long commenterUserId;

    /** 评论全文。 */
    private String commentContent;

    /** 评论图片 URL；无图时必须是空数组而非 null。 */
    private List<String> commentImages;

    /** 命中 bot 的结构化标记，必须非 null。 */
    private Boolean mentionedBot;

    /** mentioned=文本 @；replied=直接回复 bot 评论。 */
    private String botTriggerKind;

    /** 触发评论父级 ID，可空。 */
    private Long parentId;

    /** 触发评论的被回复对象 ID，可空。 */
    private Long replyCommentId;

    // 【字段冻结约定】以上字段与 QuantaBot 侧 CommentEventConsumer 的 Pydantic 模型一一对应，
    // 属于跨语言契约（同 content 包 BotContentSyncVO 的"契约字段冻结"先例）：改名 / 删字段
    // 都是对端升级事故；parentId/replyCommentId 供对端还原楼层结构。producer 侧在
    // CommentEventProducer.createBotMentionEvent 组装，值全部来自已通过审核的评论实体，
    // 没有任何字段由客户端事件外传入。
}
