package com.quanta.demo0.notification.service;

import com.quanta.demo0.notification.entity.Notification;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;

/**
 * 通知消费落库服务接口。
 *
 * 从 NotificationConsumer 中拆出的独立接口：消费者只负责抢占结果分派和
 * ack/nack 编排，"insert 通知 + Inbox 记账"这两步的原子性收进实现类，
 * 用 @Transactional 绑成一个事务（见 NotificationConsumeServiceImpl）。
 */
public interface NotificationConsumeService {

    /**
     * 通知落库和 Inbox SUCCESS 在同一个事务中完成。
     *
     * 【坑】返回 false 类的失败不会发生——租约丢失直接抛 IllegalStateException
     * 回滚事务；调用方（消费者）捕获异常后转入重试/判死流程。
     */
    Notification saveAndMarkSuccess(NotificationEventMessage message, String consumerName, String instanceId);
}
