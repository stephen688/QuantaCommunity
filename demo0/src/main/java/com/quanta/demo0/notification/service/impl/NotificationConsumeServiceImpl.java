package com.quanta.demo0.notification.service.impl;

import com.alibaba.fastjson.JSON;
import com.quanta.demo0.notification.entity.Notification;
import com.quanta.demo0.notification.mapper.NotificationMapper;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.platform.mq.service.InboxEventService;
import com.quanta.demo0.notification.service.NotificationConsumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 通知消费落库服务：把 MQ 消息转成 tb_notification 的一行，并在同一事务里给 Inbox 记账。
 *
 * ============================================================
 * 【为什么 insert 通知和 Inbox markSuccess 必须同一个事务？】
 * ============================================================
 * 拆成两步会有两个致命窗口：先 insert 后记账，中间崩溃 → 重投后再插一条（重复通知）；
 * 先记账后 insert，中间崩溃 → 事件被标记成功但通知永远不存在（丢通知）。
 * 放进 @Transactional 后两者原子提交：**要么通知落库且事件标记 SUCCESS，要么一起
 * 回滚等待重投。** markSuccess 返回 false 说明 60 秒租约已被其他实例抢走
 * （markSuccessByOwner 的 UPDATE 带 status='PROCESSING' AND locked_by 条件），
 * 此时必须抛异常回滚插入——宁可整单重做，不能以失败者的身份提交数据。
 */
@Service
@RequiredArgsConstructor
public class NotificationConsumeServiceImpl implements NotificationConsumeService {

    private final NotificationMapper notificationMapper;
    private final InboxEventService inboxEventService;

    /**
     * 通知落库 + Inbox 记账，原子完成。
     *
     * 【返回值的意义】返回的 Notification 带数据库自增 id（insert 的
     * useGeneratedKeys 回填），调用方（消费者）随后用它组装 WebSocket 推送 VO。
     * 调用方拿到返回值即代表事务已提交、事件已标记 SUCCESS，可以放心 ACK。
     */
    @Override
    @Transactional
    public Notification saveAndMarkSuccess(NotificationEventMessage message, String consumerName, String instanceId) {
        Notification notification = buildNotification(message);
        notificationMapper.insert(notification);

        // markSuccess 的 UPDATE 带 locked_by 条件：返回 false = 租约被抢，抛异常让整个事务回滚
        boolean updated = inboxEventService.markSuccess(consumerName, message.getEventId(), instanceId);

        if (!updated) {
            throw new IllegalStateException("通知 Inbox 已失去处理权，不能标记 SUCCESS");
        }

        return notification;
    }

    /**
     * 消息 → 通知实体的唯一转换点。
     *
     * 【初值语义】isRead=0：落库即未读，红点由此而来；isDeleted=0：逻辑删除位，
     * 读侧所有 SQL 都带 is_deleted=0 过滤；payload 是 Map 序列化成 JSON 字符串
     * 存库，出库时原样透传给前端（NotificationVO.payload）。
     */
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
