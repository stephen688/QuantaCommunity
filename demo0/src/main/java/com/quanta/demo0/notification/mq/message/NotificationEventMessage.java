package com.quanta.demo0.notification.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知推送消息实体类
 * 作用：定义通知消息的数据结构，生产者和消费者通过这个类传递数据
 *
 * 这一个类贯穿整条链路：业务域组装 → Outbox 表 payload（JSON 字符串）→
 * OutboxRouteRegistry 反序列化并投递 → 消费者直接消费。所有阶段用同一个形态，
 * 中途不换对象。
 *
 * ============================================================
 * 【消息为什么要自包含（文案、跳转字段全部随消息走）？】
 * ============================================================
 * content 由生产方按业务场景拼好（如 identity 审核："你的身份认证已通过"），
 * payload 带前端跳转所需 ID（authId/contentId/commentId/auditResult 等）。
 * 消费者拿到消息即可落库 + 推送，**不回头查任何业务表**——通知域与业务域
 * 只通过这个消息类耦合，新增业务方接入不用改通知域代码。
 * 代价是消息体积变大：Outbox payload 有 32 KB 上限（OutboxEventAppender），
 * 生产方要控制 content 和 payload 的尺寸。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NotificationEventMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 接收通知的用户 ID（收件人）
     * 作用：消费者根据这个 ID 确定通知推送给谁
     */
    private Long recipientUserId;

    /**
     * 触发通知的用户 ID（触发者，系统通知可为空）
     * 作用：前端显示"xxx 评论了你的内容"中的 xxx
     */
    private Long actorUserId;

    /**
     * 通知类型（如 COMMENT_ON_CONTENT、LIKE_CONTENT 等）
     * 作用：消费者根据类型决定通知内容和前端跳转逻辑
     */
    private String type;

    /**
     * 通知摘要内容
     * 作用：前端直接显示的通知文本
     */
    private String content;

    /**
     * 扩展字段（关联 ID、审核结果等）
     * 作用：存储 contentId、commentId、answerId、auditResult、rejectReason 等
     * 前端根据这些字段跳转到对应页面
     *
     * 【形态在链路中变化】内存里是 Map，落库/进 Outbox 时由消费方序列化成
     * JSON 字符串存 tb_notification.payload，前端拿到的是 JSON 文本。
     */
    private Map<String, Object> payload;

    /**
     * 消息创建时间
     * 作用：记录消息发送时间，方便调试和监控
     */
    private LocalDateTime createdAt;


    /**
     * Outbox 生成的事件唯一 ID。
     *
     * 【整条链路的幂等凭据】由 NotificationEventProducer 生成（UUID），
     * 缺了它消费者会直接把消息送进死信队列（无法在 Inbox 记账）。
     */
    private String eventId;

    /**
     * 当前固定为 NOTIFICATION_REQUESTED。
     *
     * OutboxRouteRegistry 凭这个字段决定投到哪个交换机、用什么路由键、
     * payload 反序列化成什么类——通知域不需要消费者自己判断消息种类。
     */
    private String eventType;

    /**
     * 通知事件真正发生的时间。
     *
     * 由生产方在业务事务里写入（与 createdAt 同值赋出），业务时刻不随
     * MQ 重试/延迟漂移，适合排查"通知为什么迟到"。
     */
    private LocalDateTime occurredAt;

    /**
     * 通知消费者重试次数。
     *
     * 【随消息走】每次消费侧重试转投前 +1，判死与否看它是否达到 3；
     * 初始 0 由 NotificationEventProducer 写入并序列化进 Outbox payload，
     * Outbox 侧重投原样重发，不会改动这个值。
     */
    private Integer retryCount;
}