package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.InboxEvent;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.enums.InboxEventStatus;
import com.quanta.demo0.mapper.InboxEventMapper;
import com.quanta.demo0.mapper.OutboxEventMapper;
import com.quanta.demo0.mq.message.*;
import com.quanta.demo0.service.InboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InboxEventServiceImpl implements InboxEventService {

    /**
     * 一个消费者拿到消息后，拥有 60 秒处理权。
     */
    private static final long LEASE_SECONDS = 60L;

    private final InboxEventMapper inboxEventMapper;

    private final OutboxEventMapper outboxEventMapper;

    /**
     * 尝试登记并抢占审核任务。
     */
    @Override
    @Transactional
    public InboxAcquireResult acquire(String consumerName, String instanceId, ModerationTaskMessage task) {
        Integer retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        return acquireEvent(consumerName, instanceId, task.getEventId(), task.getEventType(), task.getTargetType().name(), task.getTargetId(), retryCount);
    }

    /**
     * 尝试登记并抢占通知消息。
     */
    @Override
    @Transactional
    public InboxAcquireResult acquire(String consumerName, String instanceId, NotificationEventMessage message) {
        Integer retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        return acquireEvent(consumerName, instanceId, message.getEventId(), message.getEventType(), "NOTIFICATION", message.getRecipientUserId(), retryCount);
    }

    @Override
    @Transactional
    public InboxAcquireResult acquire(String consumerName, String instanceId, FeedPushMessage message) {
        Integer retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        return acquireEvent(consumerName, instanceId, message.getEventId(), message.getEventType(), "CONTENT", message.getContentId(), retryCount);
    }

    @Override
    @Transactional
    public InboxAcquireResult acquire(String consumerName, String instanceId, FeedDeleteMessage message) {
        Integer retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        return acquireEvent(consumerName, instanceId, message.getEventId(), message.getEventType(), "CONTENT", message.getContentId(), retryCount);
    }

    @Override
    @Transactional
    public InboxAcquireResult acquire(String consumerName, String instanceId, HotScoreMessage message) {
        Integer retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        return acquireEvent(consumerName, instanceId, message.getEventId(), message.getEventType(), "CONTENT", message.getContentId(), retryCount);
    }
    /**
     * 使用 consumerName + eventId 登记 ES 校准事件。
     */
    @Override
    @Transactional
    public InboxAcquireResult acquire(String consumerName, String instanceId, SearchReconcileMessage message) {
        Integer retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();

        return acquireEvent(
                consumerName,
                instanceId,
                message.getEventId(),
                message.getEventType(),
                message.getTargetType(),
                message.getTargetId(),
                retryCount
        );
    }

    /**
     * 尝试登记并抢占事件。
     */
    private InboxAcquireResult acquireEvent(String consumerName, String instanceId, String eventId, String eventType, String aggregateType, Long aggregateId, Integer retryCount) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockedUntil = now.plusSeconds(LEASE_SECONDS);

        OutboxEvent outboxEvent = outboxEventMapper.selectByEventId(eventId);

        InboxEvent newInboxEvent = InboxEvent.builder()
                .eventId(eventId)
                .consumerName(consumerName)
                .outboxEventId(outboxEvent == null ? null : outboxEvent.getId())
                .eventType(eventType)
                .aggregateType(outboxEvent == null ? aggregateType : outboxEvent.getAggregateType())
                .aggregateId(outboxEvent == null ? aggregateId : outboxEvent.getAggregateId())
                .status(InboxEventStatus.PROCESSING.getCode())
                .retryCount(retryCount)
                .lockedBy(instanceId)
                .lockedUntil(lockedUntil)
                .replayCount(0)
                .build();

        try {
            int insertedRows = inboxEventMapper.insertIfAbsent(newInboxEvent);

            if (insertedRows != 1) {
                throw new IllegalStateException("Inbox 事件登记失败");
            }

            return InboxAcquireResult.ACQUIRED;
        } catch (DuplicateKeyException ignored) {
            // consumer_name + event_id 已经存在，继续检查原来的处理状态。
        }

        InboxEvent existingEvent = inboxEventMapper.selectByConsumerAndEvent(consumerName, eventId);

        if (existingEvent == null) {
            throw new IllegalStateException("Inbox 事件查询失败");
        }

        if (InboxEventStatus.SUCCESS.getCode().equals(existingEvent.getStatus())) {
            return InboxAcquireResult.ALREADY_SUCCESS;
        }

        if (InboxEventStatus.DEAD.getCode().equals(existingEvent.getStatus())) {
            return InboxAcquireResult.DEAD;
        }

        if (existingEvent.getLockedUntil() != null && existingEvent.getLockedUntil().isAfter(now)) {
            return InboxAcquireResult.BUSY;
        }

        int updatedRows = inboxEventMapper.claimExpired(consumerName, eventId, instanceId, lockedUntil, now);

        return updatedRows == 1 ? InboxAcquireResult.ACQUIRED : InboxAcquireResult.BUSY;
    }






    @Override
    @Transactional
    public boolean markSuccess(
            String consumerName,
            String eventId,
            String instanceId
    ) {
        int updatedRows =
                inboxEventMapper.markSuccessByOwner(
                        consumerName,
                        eventId,
                        instanceId,
                        LocalDateTime.now()
                );

        return updatedRows == 1;
    }

    @Override
    @Transactional
    public boolean markRetry(
            String consumerName,
            String eventId,
            String instanceId,
            Integer retryCount,
            LocalDateTime nextRetryTime,
            String lastError
    ) {
        int updatedRows =
                inboxEventMapper.markRetryByOwner(
                        consumerName,
                        eventId,
                        instanceId,
                        retryCount,
                        nextRetryTime,
                        truncateError(lastError)
                );

        return updatedRows == 1;
    }

    @Override
    @Transactional
    public boolean markDead(
            String consumerName,
            String eventId,
            String instanceId,
            String lastError
    ) {
        int updatedRows =
                inboxEventMapper.markDeadByOwner(
                        consumerName,
                        eventId,
                        instanceId,
                        truncateError(lastError),
                        LocalDateTime.now()
                );

        return updatedRows == 1;
    }

    /**
     * 数据库 last_error 最多保存 2000 个字符。
     */
    private String truncateError(String lastError) {
        if (lastError == null) {
            return "未知错误";
        }

        if (lastError.length() <= 2000) {
            return lastError;
        }

        return lastError.substring(0, 2000);
    }
}
