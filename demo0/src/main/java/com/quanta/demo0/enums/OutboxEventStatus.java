package com.quanta.demo0.enums;

import lombok.Getter;

/**
 * Outbox 事件状态。
 *
 * PENDING：
 * 事件已经保存，等待发送 RabbitMQ。
 * PROCESSING：
 * 某个后端实例正在发送。
 * SENT：
 * RabbitMQ 已经确认收到并成功路由。
 * DEAD：
 * 多次发送失败，等待管理员处理。
 */
@Getter
public enum OutboxEventStatus {

    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    SENT("SENT"),
    DEAD("DEAD");

    private final String code;

    OutboxEventStatus(String code) {
        this.code = code;
    }
}