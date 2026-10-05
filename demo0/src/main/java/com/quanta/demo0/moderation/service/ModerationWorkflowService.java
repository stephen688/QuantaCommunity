package com.quanta.demo0.moderation.service;

import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.moderation.result.ModerationWorkflowResult;

/**
 * 审核消息工作流：负责可靠消费、审核供应商调用、结果落库和 Inbox 状态推进。
 *
 * <p>RabbitMQ channel 的 ACK/NACK 由监听器执行，避免业务工作流依赖传输层对象。</p>
 *
 * ============================================================
 * 【这个接口是机审异步链路的"业务主入口"】
 * ============================================================
 * ModerationConsumer 收到 moderation.queue 的消息后只做一件事：
 * 调 {@link #process} 并按返回值确认消息。抢占幂等（Inbox）、调云 API、
 * 更新业务状态、安排重试全部在这一个方法里编排。
 * 注意区分两层返回：**业务结论在 ModerationResult（审出了什么），
 * 本接口的返回值 ModerationWorkflowResult 只描述消息层该怎么办（ACK/REQUEUE/DEAD）**。
 * 完整链路图与重试语义见 ModerationWorkflowServiceImpl 类注释。
 */
public interface ModerationWorkflowService {

    ModerationWorkflowResult process(ModerationTaskMessage task);
}
