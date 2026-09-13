package com.quanta.demo0.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbox 事件对应的 RabbitMQ 路由信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxRoute {

    /**
     * RabbitMQ 交换机。
     */
    private String exchange;

    /**
     * RabbitMQ 路由键。
     */
    private String routingKey;

    /**
     * 反序列化后的消息对象。
     */
    private Object message;
}