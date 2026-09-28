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
     * 在评论审核通过事务中创建 bot 触发事件。
     *
     * @param comment 评论实体（审核已通过）
     * @param imageUrls 评论图片 URL，可空
     * @param botTriggerKind mentioned 或 replied
     * @return eventId
     */
    String createBotMentionEvent(
            ContentComment comment,
            List<String> imageUrls,
            String botTriggerKind
    );


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
     * 用户行为事件（画像更新信号）。与业务写同事务调用。
     * behaviorType 仅允许 LIKE / COLLECT / COMMENT / VIEW。
     * 返回 eventId，与其它 create* 事件入口同构，便于调用方记录与排查。
     */
    String createUserBehaviorEvent(Long userId, Long contentId, String behaviorType);

    /**
     * 用户行为事件（画像更新信号），指定稳定 eventId 的重载。
     * 浏览对账任务用 user.behavior.browse:{browseHistoryId} 固定格式，
     * 保证同一行重复转发（watermark 未推进重扫）被 Inbox 幂等挡住；
     * 赞/藏/评路径用三参版本（内部生成 UUID）。eventId 传 null 时同样内部生成。
     */
    String createUserBehaviorEvent(Long userId, Long contentId, String behaviorType, String eventId);


    /**
     * 在业务事务中创建 ES 校准事件。
     *
     * @param targetType CONTENT 或 ANSWER
     * @param targetId 帖子 ID 或回答 ID
     * @param triggerType 触发原因，只用于排查
     */
    String createSearchReconcileEvent(String targetType, Long targetId, String triggerType);

    /** 与审核通过或回填入队事务同提交，只带 contentId，不同步调用 LLM。 */
    String createContentTopicTagEvent(Long contentId);

    /** 与显式偏好事实同事务；保留 Agent 同次提交的稳定 eventId。 */
    String createProfileUpdatedEvent(Long userId, String eventId);

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
