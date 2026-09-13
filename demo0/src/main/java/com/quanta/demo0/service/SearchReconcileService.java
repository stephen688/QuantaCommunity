package com.quanta.demo0.service;

/**
 * Elasticsearch 索引校准服务。
 * 消费者只负责消息流程，具体帖子和回答的 ES 操作统一放在这里。
 */
public interface SearchReconcileService {

    /**
     * 根据 MySQL 当前状态校准 ES 文档。
     *
     * @param targetType CONTENT 或 ANSWER
     * @param targetId 帖子 ID 或回答 ID
     */
    void reconcileSearchIndex(String targetType, Long targetId);
}