package com.quanta.demo0.service;

import com.quanta.demo0.entity.Notification;
import com.quanta.demo0.mq.message.NotificationEventMessage;

public interface NotificationConsumeService {

    /**
     * 通知落库和 Inbox SUCCESS 在同一个事务中完成。
     */
    Notification saveAndMarkSuccess(NotificationEventMessage message, String consumerName, String instanceId);
}
