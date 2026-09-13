package com.quanta.demo0.service;

import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.mq.message.ModerationTaskMessage;

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
