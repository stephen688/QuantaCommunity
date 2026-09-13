package com.quanta.demo0.service;

/**
 * 回答审核服务：AI 自动审核或关闭 AI 时的兜底通过/驳回逻辑
 */
public interface AnswerAuditService {

    /**
     * 审核通过：更新状态并同步 ES/向量库
     */
    void approveAnswer(Long answerId);

    /**
     * 审核驳回：仅当仍为待审时更新状态并通知作者
     */
    void rejectAnswer(Long answerId, String rejectReason);
}
