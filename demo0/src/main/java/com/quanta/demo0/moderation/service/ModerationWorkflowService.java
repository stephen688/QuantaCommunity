package com.quanta.demo0.moderation.service;

import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;
import com.quanta.demo0.moderation.result.ModerationWorkflowResult;

/**
 * 审核消息工作流：负责可靠消费、审核供应商调用、结果落库和 Inbox 状态推进。
 *
 * <p>RabbitMQ channel 的 ACK/NACK 由监听器执行，避免业务工作流依赖传输层对象。</p>
 */
public interface ModerationWorkflowService {

    ModerationWorkflowResult process(ModerationTaskMessage task);
}
