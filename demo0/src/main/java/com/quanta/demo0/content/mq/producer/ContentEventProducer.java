package com.quanta.demo0.content.mq.producer;

import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.platform.mq.enums.OutboxEventType;
import com.quanta.demo0.platform.mq.producer.OutboxEventAppender;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 内容域可靠事件生产者：构造内容审核、Feed 校准和主题标签消息。
 *
 * 边界：只读取内容域实体并调用 platform/mq 通用追加能力，不直接访问 Outbox Mapper。
 *
 * ============================================================
 * 【Outbox 生产端的三个细节，面试讲可靠性时全用得上】
 * ============================================================
 * 1. **appendIfAbsent + 确定性 eventId**（见 createContentTopicTagEvent）：
 *    eventId 不用随机 UUID，而是 nameUUIDFromBytes("content-topic-v1:" + contentId)
 *    —— 同一帖子无论登记多少次，eventId 都相同，outbox 表唯一键让重复登记自然被吞。
 *    这是**生产端幂等**：审核通过事务重放（重试/补偿）不会堆出重复打标任务。
 *    对比 createContentModerationEvent 用随机 UUID：审核事件不需要去重，
 *    因为消费端有 Inbox 兜着 —— 幂等做在哪一层是设计选择，但要明确。
 *
 * 2. **@Transactional 加在 Producer 方法上**：默认传播 REQUIRED，
 *    加入调用方（发布/审核）的事务 —— Outbox 行与业务行原子提交，
 *    这就是 Outbox 模式的落点。单独调用时它自己开事务。
 *
 * 3. **消息体大小校验**（32KB 上限 + 图片 URL 4096 字节）：
 *    Outbox 的 payload 是 DB 字段，塞超大消息会拖慢 Dispatcher 扫表和 MQ 传输。
 *    **大内容走引用（contentId），不走值** —— 消费者拿到 id 自己查库。
 */
@Service
@RequiredArgsConstructor
public class ContentEventProducer {

    private static final int MAX_IMAGE_URL_BYTES = 4096;

    private final OutboxEventAppender outboxEventAppender;
    private final FeedEventProducer feedEventProducer;

    /**
     * 在内容发布事务中创建审核事件。
     *
     * 【eventId 为什么用随机 UUID？】与 createContentTopicTagEvent 的确定性 UUID 相反：
     * 每次发布都是一次全新的审核请求，业务上不存在"同一事实重复登记"，
     * 去重交给消费端 Inbox —— **幂等做在生产端还是消费端，取决于重复登记是否可能**。
     * 【payload 为什么带原文】审核对象是提交那一刻的标题/正文/图片，不能回读——
     * 期间帖子可能已被作者修改，审核证据就变了（与主题标签消息的"带引用"对比见消息类注释）。
     * 【失败语义】payload 超 32KB 或 Outbox 插入失败都抛 ContentFailedException，
     * 让发布事务整体回滚——"帖子发出去了、审核任务却丢了"是不允许出现的状态。
     */
    @Transactional
    public String createContentModerationEvent(Content content, List<String> imageUrls) {
        List<String> safeImageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        validateImageUrls(safeImageUrls);
        requireContent(content, "审核事件");

        String eventId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now();
        ModerationTaskMessage message = ModerationTaskMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.MODERATION_REQUESTED.getCode())
                .occurredAt(occurredAt)
                .targetType(ModerationTargetType.CONTENT)
                .targetId(content.getContentId())
                .publisherUserId(content.getPublishUserId())
                .title(content.getTitle())
                .content(content.getContent())
                .imageUrls(safeImageUrls)
                .createdAt(occurredAt)
                .retryCount(0)
                .build();
        try {
            return outboxEventAppender.append(
                    eventId,
                    OutboxEventType.MODERATION_REQUESTED.getCode(),
                    ModerationTargetType.CONTENT.name(),
                    content.getContentId(),
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw payloadTooLarge(exception);
        } catch (OutboxInsertFailedException exception) {
            throw new ContentFailedException("创建帖子审核任务失败");
        }
    }

    /**
     * 帖子审核通过时创建 Feed 校准事件。
     *
     * 【事务语义】@Transactional(REQUIRED) 加入调用方（审核通过）的事务，
     * Outbox 行与审核状态同批提交——事务回滚，事件一起消失。
     * 消息构造委托 feed 域的 FeedEventProducer，本方法只负责把 Content 实体
     * 转成跨域快照（ContentSnapshotVO）——**跨域传快照，不传实体**。
     */
    @Transactional
    public String createFeedUpsertEvent(Content content) {
        requireContent(content, "Feed 事件");
        return feedEventProducer.createFeedUpsertEvent(toSnapshot(content));
    }

    /**
     * 帖子驳回或删除时创建 Feed 校准事件。
     *
     * 【事务语义】与 createFeedUpsertEvent 相同（REQUIRED 加入调用方事务）。
     * 删除/驳回走 DELETE 语义，Feed 消费者据此把帖子移出推荐流——
     * 进池（UPSERT）和出池（DELETE）是一对对称事件，写扩散的地方清理也要对称。
     */
    @Transactional
    public String createFeedDeleteEvent(Content content) {
        requireContent(content, "Feed 事件");
        return feedEventProducer.createFeedDeleteEvent(toSnapshot(content));
    }

    /**
     * 为指定内容登记稳定的主题标签任务。
     *
     * 【eventId 为什么是确定性 UUID？】nameUUIDFromBytes("content-topic-v1:" + contentId)
     * 让"同一帖子"永远映射到同一个 eventId —— appendIfAbsent 靠 outbox 表唯一键
     * 把重复登记**原子地吞掉**（重复时什么也不发生）。三个调用方（机审通过、
     * 管理端通过、存量回填）谁先到谁生效，天然互斥，无需额外加锁。
     * 【重复调用会怎样】无副作用 —— 这是"审核通过可能被多条链路触发"这一事实
     * 在生产端的兜底；即便这里漏防，消费端还有 Inbox 幂等第二道闸。
     * 前缀 "content-topic-v1" 是事件版本号：打标语义升级时换前缀即可新旧并存。
     */
    @Transactional
    public String createContentTopicTagEvent(Long contentId) {
        if (contentId == null || contentId <= 0) {
            throw new ContentFailedException("主题打标缺少有效帖子 ID");
        }
        String eventId = UUID.nameUUIDFromBytes(
                ("content-topic-v1:" + contentId).getBytes(StandardCharsets.UTF_8)).toString();
        ContentTopicTagMessage message = ContentTopicTagMessage.builder()
                .eventId(eventId)
                .eventType(OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode())
                .contentId(contentId)
                .occurredAt(LocalDateTime.now())
                .retryCount(0)
                .build();
        try {
            return outboxEventAppender.appendIfAbsent(
                    eventId,
                    OutboxEventType.CONTENT_TOPIC_TAG_REQUESTED.getCode(),
                    ModerationTargetType.CONTENT.name(),
                    contentId,
                    message);
        } catch (OutboxEventAppender.PayloadTooLargeException exception) {
            throw payloadTooLarge(exception);
        }
    }

    private void requireContent(Content content, String eventName) {
        if (content == null
                || content.getContentId() == null
                || content.getPublishUserId() == null
                || content.getContentType() == null) {
            throw new ContentFailedException(eventName + "缺少帖子必要信息");
        }
    }

    private ContentSnapshotVO toSnapshot(Content content) {
        return ContentSnapshotVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .tags(content.getTags())
                .publishUserId(content.getPublishUserId())
                .auditStatus(content.getAuditStatus())
                .isDeleted(content.getIsDeleted())
                .createTime(content.getCreateTime())
                .updateTime(content.getUpdateTime())
                .likedCount(content.getLiked())
                .commentCount(content.getCommentCount())
                .collectCount(content.getCollectCount())
                .build();
    }

    /** 逐个校验图片 URL 字节数（单条 4096 上限）——防单张图片的地址撑爆 32KB payload 预算。 */
    private void validateImageUrls(List<String> imageUrls) {
        for (String imageUrl : imageUrls) {
            if (imageUrl != null
                    && imageUrl.getBytes(StandardCharsets.UTF_8).length > MAX_IMAGE_URL_BYTES) {
                throw new ContentFailedException("单个图片地址不能超过 4096 字节");
            }
        }
    }

    /** 把平台层的 PayloadTooLargeException 翻译成内容域业务异常——错误类型不跨域泄漏。 */
    private ContentFailedException payloadTooLarge(OutboxEventAppender.PayloadTooLargeException exception) {
        return new ContentFailedException("审核任务超过 32 KB，请缩短内容或图片地址");
    }
}
