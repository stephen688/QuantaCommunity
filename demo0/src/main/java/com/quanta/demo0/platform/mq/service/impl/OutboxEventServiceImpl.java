package com.quanta.demo0.platform.mq.service.impl;

import com.quanta.demo0.platform.mq.entity.OutboxEvent;
import com.quanta.demo0.platform.mq.exception.OutboxInsertFailedException;
import com.quanta.demo0.platform.mq.enums.OutboxEventStatus;
import com.quanta.demo0.platform.mq.mapper.OutboxEventMapper;
import com.quanta.demo0.platform.mq.properties.OutboxDispatchProperties;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.platform.web.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台消息能力：持久化通用 Outbox 记录并维护发送租约。
 *
 * 业务域负责构造消息和序列化 payload；本类只接收平台自己的
 * {@link OutboxEvent} 元数据，确保业务实体不会反向进入 platform/mq。
 */
@Service
@RequiredArgsConstructor
public class OutboxEventServiceImpl implements OutboxEventService {

    private final OutboxEventMapper outboxEventMapper;
    private final OutboxDispatchProperties outboxDispatchProperties;

    /**
     * 追加一条 Outbox 记录。调用方已有事务时加入该事务，插入失败抛出异常触发回滚。
     */
    @Override
    @Transactional
    public String append(OutboxEvent event) {
        OutboxEvent normalized = normalize(event);
        if (outboxEventMapper.insert(normalized) != 1) {
            throw new OutboxInsertFailedException("创建 Outbox 事件失败");
        }
        return normalized.getEventId();
    }

    /**
     * 以稳定 eventId 原子追加一条 Outbox 记录。唯一键已存在时保留旧记录。
     */
    @Override
    @Transactional
    public String appendIfAbsent(OutboxEvent event) {
        OutboxEvent normalized = normalize(event);
        outboxEventMapper.insertIfAbsent(normalized);
        return normalized.getEventId();
    }

    /**
     * 在短事务中查询并抢占一批等待发送的事件。
     */
    @Override
    @Transactional
    public List<OutboxEvent> claimBatch(String instanceId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockedUntil = now.plusSeconds(outboxDispatchProperties.getLeaseSeconds());
        List<OutboxEvent> candidates = outboxEventMapper.selectClaimableForUpdate(
                now,
                outboxDispatchProperties.getBatchSize());

        List<OutboxEvent> claimedEvents = new ArrayList<>();
        for (OutboxEvent event : candidates) {
            int updatedRows = outboxEventMapper.claimForProcessing(
                    event.getId(), instanceId, lockedUntil, now);
            if (updatedRows == 1) {
                event.setStatus(OutboxEventStatus.PROCESSING.getCode());
                event.setLockedBy(instanceId);
                event.setLockedUntil(lockedUntil);
                claimedEvents.add(event);
            }
        }
        return claimedEvents;
    }

    /**
     * Confirm ACK 且未被 Return 时标记发送成功。
     */
    @Override
    @Transactional
    public boolean markSent(Long id, String instanceId) {
        return outboxEventMapper.markSentByOwner(id, instanceId, LocalDateTime.now()) == 1;
    }

    /**
     * 发送失败后回到 PENDING，并记录下一次重试时间。
     */
    @Override
    @Transactional
    public boolean markRetry(Long id, String instanceId, Integer retryCount,
                             LocalDateTime nextRetryTime, String lastError) {
        return outboxEventMapper.markRetryByOwner(
                id,
                instanceId,
                retryCount,
                nextRetryTime,
                truncateError(lastError)) == 1;
    }

    /**
     * 自动重试耗尽后标记 DEAD。
     */
    @Override
    @Transactional
    public boolean markDead(Long id, String instanceId, String lastError) {
        return outboxEventMapper.markDeadByOwner(
                id,
                instanceId,
                truncateError(lastError)) == 1;
    }

    private OutboxEvent normalize(OutboxEvent event) {
        if (event == null
                || !StringUtils.hasText(event.getEventId())
                || !StringUtils.hasText(event.getEventType())
                || !StringUtils.hasText(event.getAggregateType())
                || event.getAggregateId() == null
                || event.getPayload() == null) {
            throw new IllegalArgumentException("Outbox 事件缺少必要元数据");
        }

        LocalDateTime now = LocalDateTime.now();
        event.setStatus(event.getStatus() == null
                ? OutboxEventStatus.PENDING.getCode()
                : event.getStatus());
        event.setTraceId(TraceContext.resolveEvent(event.getTraceId(), event.getEventId()));
        event.setRetryCount(event.getRetryCount() == null ? 0 : event.getRetryCount());
        event.setNextRetryTime(event.getNextRetryTime() == null ? now : event.getNextRetryTime());
        event.setReplayCount(event.getReplayCount() == null ? 0 : event.getReplayCount());
        return event;
    }

    /**
     * 数据库 last_error 最大长度为 2000。
     */
    private String truncateError(String lastError) {
        if (lastError == null) {
            return "未知错误";
        }
        return lastError.length() <= 2000 ? lastError : lastError.substring(0, 2000);
    }
}
