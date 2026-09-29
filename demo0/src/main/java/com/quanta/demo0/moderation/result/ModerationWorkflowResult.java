package com.quanta.demo0.moderation.result;

/**
 * 审核工作流完成后，消息监听器需要执行的 RabbitMQ 动作。
 */
public enum ModerationWorkflowResult {

    /** 当前消息已经完成可靠消费，可以确认。 */
    ACK,

    /** 当前消息暂时不能确认，交回 RabbitMQ 重新投递。 */
    REQUEUE,

    /** 当前消息进入死信队列，不再重新投递。 */
    DEAD
}
