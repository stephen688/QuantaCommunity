package com.quanta.demo0.platform.mq.service;

import com.quanta.demo0.platform.mq.entity.OutboxEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台 Outbox 持久化与发送租约服务。
 *
 * 业务域只需把已序列化的消息和事件元数据交给本接口；本接口不暴露任何业务实体。
 */
public interface OutboxEventService {

    /**
     * 在调用方事务中追加一条可靠事件。
     */
    String append(OutboxEvent event);

    /**
     * 以稳定 eventId 原子追加事件；已存在时保留原记录。
     */
    String appendIfAbsent(OutboxEvent event);

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
    boolean markRetry(Long id, String instanceId, Integer retryCount,
                      LocalDateTime nextRetryTime, String lastError);

    /**
     * 标记事件彻底失败。
     */
    boolean markDead(Long id, String instanceId, String lastError);
}
