package com.quanta.demo0.comment.mq.producer;

import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.comment.mq.message.BotMentionMessage;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 评论域可靠事件生产者：构造评论审核和 Bot 触发消息。
 *
 * ============================================================
 * 【为什么只往 Outbox 表里插一行，而不直接发 RabbitMQ？】
 * ============================================================
 * "先写库再发 MQ"的顺序有两难：先发消息后提交事务，事务回滚了消息却已出去
 * （幽灵事件）；先提交事务再发消息，进程在中间崩溃则消息永远丢失。
 * **Outbox 方案把"发消息"降级成"和业务数据同一个事务里插一张表"**：
 * 本类两个方法都是 @Transactional（REQUIRED 传播，并入调用方如 sendComment /
 * approveComment 的既有事务），Outbox 行与评论行要么一起提交、要么一起回滚。
 * 真正的投递由 platform.mq 的 OutboxDispatcher 事后扫描完成（publisher-confirm
 * 可靠发送，见 application.yml 全局配置），At-Least-Once 由消费端 Inbox 幂等兜底。
 * 因此本类只做"构造 payload + 落 Outbox"，不碰 RabbitTemplate。
 *
 * 异常翻译约定：OutboxEventAppender 抛出的两种平台异常在此翻译成业务异常，
 * 让事务回滚并给用户可读的错误（超限 / Outbox 插入失败都必须让整个发布失败）。
 */
@Service
@RequiredArgsConstructor
public class CommentEventProducer {

    // 单张图片 URL 的字节上限（UTF-8）。32KB 是整条 Outbox payload 的硬上限
    //（OutboxEventAppender.MAX_PAYLOAD_BYTES），这里先行拦截最占体积的图片 URL，
    // 给出比"payload 超限"更具体的报错。
    private static final int MAX_IMAGE_URL_BYTES = 4096;

    private final OutboxEventAppender outboxEventAppender;

    /**
     * 在评论发布事务中创建审核事件。
     *
     * 【调用时机】sendComment 第 7 步：AI 审核开关（AliyunModerationProperties 总开关
     * + comment 目标开关）都打开时调用，事件与评论、图片同一事务落库。
     * 事件类型 MODERATION_REQUESTED，聚合维度固定 COMMENT + 评论 ID，
     * OutboxDispatcher 会把它路由到 ModerationMQConfig 的审核交换机。
     *
     * 【坑】imageUrls 一律防御性拷贝成不可变列表（List.copyOf），null 归一为空列表——
     * 防止调用方之后修改列表导致 payload 与库里不一致。
     */
    @Transactional
    public String createCommentModerationEvent(ContentComment comment, List<String> imageUrls) {
        requireComment(comment);
        List<String> safeImageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        validateImageUrls(safeImageUrls);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        ModerationTaskMessage message = ModerationTaskMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .targetType(ModerationTargetType.COMMENT)
                .targetId(comment.getCommentId())
                .publisherUserId(comment.getUserId())
                .title(null)
                .content(comment.getContent())
                .imageUrls(safeImageUrls)
                .createdAt(occurredAt)
                .retryCount(0)
                .build();
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.MODERATION_REQUESTED.getCode(),
                    ModerationTargetType.COMMENT.name(),
                    comment.getCommentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            // 平台 32KB 上限被触发 → 译成业务异常让发布事务整体回滚。
            // 这里沿用 content 包的 ContentFailedException（评论域拆分前遗留的复用），
            // 两者都被 GlobalExceptionHandler 映射为 400。
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new CommentFailedException("创建评论审核任务失败");
        }
    }

    /**
     * 在审核通过的评论事务中创建 Bot 触发事件。
     *
     * 【为什么在审核通过之后而不是发布时创建】bot 不能回复一条可能被驳回的评论：
     * 唯一调用方是 CommentAuditServiceImpl.maybeCreateBotMentionEvent，且只在
     * approveComment / approveRejectedComment 把状态改成 APPROVED 的同一事务里调用，
     * 保证"评论可见"与"bot 收到触发"要么同时发生、要么都不发生。
     *
     * 【路由去向】事件类型 BOT_MENTION_REQUESTED（C-1）。OutboxDispatcher 经
     * OutboxRouteRegistry 按此类型把它路由到 ContentMQConfig.BOT_MENTION_EXCHANGE
     * （"quantabot.exchange"）→ quantabot.comment.queue，由外部 QuantaBot（Python）消费。
     *
     * 【坑】botTriggerKind 只认 "mentioned"（文本 @）/ "replied"（直接回复 bot 评论），
     * 其他值直接抛异常——这个字段决定 QuantaBot 的回答策略，宁可在生产端拒绝也不发歧义事件。
     */
    @Transactional
    public String createBotMentionEvent(ContentComment comment, List<String> imageUrls,
                                        String botTriggerKind) {
        requireComment(comment);
        if (!"mentioned".equals(botTriggerKind) && !"replied".equals(botTriggerKind)) {
            throw new CommentFailedException("bot 触发类型非法");
        }
        List<String> safeImageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        validateImageUrls(safeImageUrls);

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        BotMentionMessage message = BotMentionMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.BOT_MENTION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .retryCount(0)
                .commentId(comment.getCommentId())
                .postId(comment.getContentId())
                .answerId(comment.getAnswerId())
                .commenterUserId(comment.getUserId())
                .commentContent(comment.getContent())
                .commentImages(safeImageUrls)
                .mentionedBot(true)
                .botTriggerKind(botTriggerKind)
                .parentId(comment.getParentId())
                .replyCommentId(comment.getReplyCommentId())
                .build();
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.BOT_MENTION_REQUESTED.getCode(),
                    ModerationTargetType.COMMENT.name(),
                    comment.getCommentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
        } catch (OutboxInsertFailedException exception) {
            throw new CommentFailedException("创建 bot 触发事件失败");
        }
    }

    /** 出事之前先自检：没有 ID / 作者的评论事件无处路由也无从追责，直接拒绝。 */
    private void requireComment(ContentComment comment) {
        if (comment == null || comment.getCommentId() == null || comment.getUserId() == null) {
            throw new CommentFailedException("评论事件缺少必要信息");
        }
    }

    /** 逐条校验图片 URL 字节数（见 MAX_IMAGE_URL_BYTES），把 payload 超限拦在 Outbox 之前。 */
    private void validateImageUrls(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            if (imageUrl != null
                    && imageUrl.getBytes(StandardCharsets.UTF_8).length > MAX_IMAGE_URL_BYTES) {
                throw new ContentFailedException("单个图片地址不能超过 4096 字节");
            }
        }
    }
}
