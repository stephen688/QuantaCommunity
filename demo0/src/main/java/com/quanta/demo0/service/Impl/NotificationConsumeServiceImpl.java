package com.quanta.demo0.service.Impl;

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.entity.Notification;
import com.quanta.demo0.mapper.NotificationMapper;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.NotificationConsumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationConsumeServiceImpl implements NotificationConsumeService {

    private final NotificationMapper notificationMapper;
    private final InboxEventService inboxEventService;

    @Override
    @Transactional
    public Notification saveAndMarkSuccess(NotificationEventMessage message, String consumerName, String instanceId) {
        Notification notification = buildNotification(message);
        notificationMapper.insert(notification);

        boolean updated = inboxEventService.markSuccess(consumerName, message.getEventId(), instanceId);

        if (!updated) {
            throw new IllegalStateException("通知 Inbox 已失去处理权，不能标记 SUCCESS");
        }

        return notification;
    }

    private Notification buildNotification(NotificationEventMessage message) {
        LocalDateTime now = LocalDateTime.now();

        return Notification.builder()
                .recipientUserId(message.getRecipientUserId())
                .actorUserId(message.getActorUserId())
                .type(message.getType())
                .content(message.getContent())
                .payload(JSON.toJSONString(message.getPayload()))
                .isRead(0)
                .createTime(now)
                .updateTime(now)
                .isDeleted(0)
                .build();
    }
}
