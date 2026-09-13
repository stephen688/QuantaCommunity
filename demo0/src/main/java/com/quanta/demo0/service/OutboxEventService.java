package com.quanta.demo0.service;

import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.mq.message.NotificationEventMessage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件服务接口。
 */
public interface OutboxEventService {

    /**
     * 在帖子发布事务中创建审核事件。
     */
    String createContentModerationEvent(
            Content content,
            List<String> imageUrls
    );

    /**
     * 在业务事务中创建通知事件。
     */
    String createNotificationEvent(
            NotificationEventMessage message,
            String aggregateType,
            Long aggregateId
    );

    /**
     * 在回答发布事务中创建审核事件。
     */
    String createAnswerModerationEvent(QuestionAnswer answer);

    /**
     * 在评论发布事务中创建审核事件。
     */
    String createCommentModerationEvent(ContentComment comment, List<String> imageUrls);


    /**
     * 帖子审核通过时创建 Feed 校准事件。
     */
    String createFeedUpsertEvent(Content content);

    /**
     * 帖子驳回或删除时创建 Feed 校准事件。
     */
    String createFeedDeleteEvent(Content content);

    /**
     * 点赞、收藏或评论数据真正变化时，创建热度重新计算事件
     */
    String createHotScoreRecalculateEvent(Long contentId, String triggerType);


    /**
     * 在业务事务中创建 ES 校准事件。
     *
     * @param targetType CONTENT 或 ANSWER
     * @param targetId 帖子 ID 或回答 ID
     * @param triggerType 触发原因，只用于排查
     */
    String createSearchReconcileEvent(String targetType, Long targetId, String triggerType);

    /**
     * 抢占一批等待发送的事件。
     */
    List<OutboxEvent> claimBatch(String instanceId);

    /**
     * 标记事件发送成功。
     */
    boolean markSent(Long id, String instanceId);

    /**
     * 标记事件等待重试。
     */
    boolean markRetry(
            Long id,
            String instanceId,
            Integer retryCount,
            LocalDateTime nextRetryTime,
            String lastError
    );

    /**
     * 标记事件彻底失败。
     */
    boolean markDead(
            Long id,
            String instanceId,
            String lastError
    );
}
