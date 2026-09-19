package com.quanta.demo0.mq.message;

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
}
