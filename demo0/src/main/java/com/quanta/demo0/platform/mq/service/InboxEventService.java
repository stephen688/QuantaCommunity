package com.quanta.demo0.platform.mq.service;

import com.quanta.demo0.platform.mq.enums.InboxAcquireResult;
import com.quanta.demo0.comment.mq.message.BotMentionMessage;
import com.quanta.demo0.content.mq.message.ContentTopicTagMessage;
import com.quanta.demo0.feed.mq.message.FeedDeleteMessage;
import com.quanta.demo0.feed.mq.message.FeedPushMessage;
import com.quanta.demo0.feed.mq.message.HotScoreMessage;
import com.quanta.demo0.feed.mq.message.ProfileReconcileMessage;
import com.quanta.demo0.feed.mq.message.UserBehaviorMessage;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.platform.mq.message.OutboxRoute;
import com.quanta.demo0.search.mq.message.SearchReconcileMessage;

import java.time.LocalDateTime;

public interface InboxEventService {

    /**
     * 尝试登记并抢占审核消息。
     */
    InboxAcquireResult acquire(
            String consumerName,
            String instanceId,
            ModerationTaskMessage task
    );

    /**
     * 尝试登记并抢占通知消息。
     */
    InboxAcquireResult acquire(String consumerName,
                               String instanceId, NotificationEventMessage message);

    /**
     * 尝试登记并抢占 Feed 新增或校准消息。
     */
    InboxAcquireResult acquire(String consumerName, String instanceId, FeedPushMessage message);

    /**
     * 尝试登记并抢占 Feed 删除或校准消息。
     */
    InboxAcquireResult acquire(String consumerName, String instanceId, FeedDeleteMessage message);

    /**
     * 尝试登记并抢占热度重新计算消息。
     */
    InboxAcquireResult acquire(String consumerName, String instanceId, HotScoreMessage message);

    /**
     * 尝试登记并抢占 ES 校准消息。
     */
    InboxAcquireResult acquire(String consumerName, String instanceId, SearchReconcileMessage message);

    /**
     * 尝试登记并抢占用户行为（画像更新）消息。
     */
    InboxAcquireResult acquire(String consumerName, String instanceId, UserBehaviorMessage message);

    /** 主题打标消费，登记同一内容事件的幂等与租约。 */
    InboxAcquireResult acquire(String consumerName, String instanceId, ContentTopicTagMessage message);

    /** 显式画像消费，仅使用事件用户标识，不依赖 HTTP 上下文。 */
    InboxAcquireResult acquire(String consumerName, String instanceId, ProfileReconcileMessage message);



    /**
     * 标记处理成功。
     */
    boolean markSuccess(
            String consumerName,
            String eventId,
            String instanceId
    );

    /**
     * 标记等待重试。
     */
    boolean markRetry(
            String consumerName,
            String eventId,
            String instanceId,
            Integer retryCount,
            LocalDateTime nextRetryTime,
            String lastError
    );

    /**
     * 标记彻底失败。
     */
    boolean markDead(
            String consumerName,
            String eventId,
            String instanceId,
            String lastError
    );
}
