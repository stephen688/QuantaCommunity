package com.quanta.demo0.moderation.service;

import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;

/**
 * 审核结果回调服务：分发机审结论并推进 Inbox 状态。
 *
 * ============================================================
 * 【先读 ModerationWorkflowServiceImpl，再读本接口】
 * ============================================================
 * "分发结果 + Inbox 标记 SUCCESS 必须同事务"这一职责在本项目有两处实现：
 * - ModerationWorkflowServiceImpl：消费主链路实际使用的实现，
 *   因为同事务段是私有方法，@Transactional 声明式事务不生效，
 *   所以用 TransactionTemplate 编程式圈定事务边界；
 * - 本接口与 ModerationResultServiceImpl：同一职责的独立封装，
 *   事务交给方法级声明式 @Transactional。
 * 当前生产消费链路（ModerationConsumer）只注入 ModerationWorkflowService，
 * 并不注入本接口——ConsumerReliabilityTests 明确断言了这一点。
 * **把本类当"同职责的对照实现"读，不要去找它并不存在的调用方。**
 */
public interface ModerationResultService {

    /**
     * 处理 Outbox 审核结果，
     * 并在同一个事务中把 Inbox 改为 SUCCESS。
     */
    void handleResultAndMarkSuccess(
            ModerationTaskMessage task,
            ModerationResult result,
            String consumerName,
            String instanceId
    );
}
