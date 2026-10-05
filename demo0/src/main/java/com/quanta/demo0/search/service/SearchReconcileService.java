package com.quanta.demo0.search.service;

/**
 * Elasticsearch 索引校准服务。
 * 消费者只负责消息流程，具体帖子和回答的 ES 操作统一放在这里。
 *
 * <p>"校准"的含义：不信任事件里带的任何业务状态，永远以 MySQL 当前
 * 数据重新推导 ES 文档应有的样子（存在则覆盖、不该存在则删除），
 * 因此天然是幂等且可重放的。</p>
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