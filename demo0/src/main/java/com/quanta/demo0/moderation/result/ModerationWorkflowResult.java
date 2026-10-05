package com.quanta.demo0.moderation.result;

/**
 * 审核工作流完成后，消息监听器需要执行的 RabbitMQ 动作。
 *
 * ============================================================
 * 【为什么要一个"传输层动作"枚举，而不是让工作流直接操作 Channel？】
 * ============================================================
 * 业务工作流（ModerationWorkflowServiceImpl）一旦拿到 Channel，
 * 就要同时背负业务正确性和消息确认两件事，测试也得 mock 传输层对象。
 * 把动作收敛成三个枚举值后，**工作流只回答"这条消息该怎么办"，
 * 真正的 basicAck / basicNack 由 ModerationConsumer.acknowledgeMessage 执行**：
 * - ACK     → basicAck（正常确认）；
 * - REQUEUE → basicNack(requeue=true)，消息立即重新入队再投；
 * - DEAD    → basicNack(requeue=false)，不重投，经主队列声明的死信交换机
 *             落入 moderation.dlx.queue（拓扑见 ModerationMQConfig）。
 * 业务结论走 ModerationResult，传输动作走本枚举——两条线不混。
 */
public enum ModerationWorkflowResult {

    /** 当前消息已经完成可靠消费，可以确认。 */
    ACK,

    /** 当前消息暂时不能确认，交回 RabbitMQ 重新投递。 */
    REQUEUE,

    /** 当前消息进入死信队列，不再重新投递。 */
    DEAD
}
