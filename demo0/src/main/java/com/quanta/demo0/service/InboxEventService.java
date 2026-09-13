package com.quanta.demo0.service;

import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.mq.message.*;

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
