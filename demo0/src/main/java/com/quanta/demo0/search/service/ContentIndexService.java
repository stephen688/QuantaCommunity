package com.quanta.demo0.search.service;

import com.github.pagehelper.Page;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.result.ReindexResult;

import java.util.List;

/**
 * 搜索域内容索引服务。
 *
 * <p>负责从内容持久化快照生成搜索文档并执行 content 索引读写；返回值使用搜索域文档，
 * 不把内容 Entity 暴露给搜索域外的编排服务。</p>
 *
 * ============================================================
 * 【方法分组：三个写入口按场景选，不要混用】
 * ============================================================
 * 单条 upsert/delete 服务 MQ 对账链路（SearchReconcileService 调用）；
 * 批量 upsert 服务人工修数；全量重建服务索引重灌。所有写操作都以
 * "MySQL 当前事实"为最终依据（实现见 ContentIndexServiceImpl 类注释）。
 * 读侧 searchContent 是用户搜索与 RAG 召回（EsRecallService）共用的入口。
 */
public interface ContentIndexService {

    /** 单条对账：按 MySQL 当前事实 upsert 或删除（发布/审核/删除事件的最终落点）。 */
    void upsertByContentId(Long contentId);

    /** 批量对账：一次 IN 查询后混合 upsert/delete；入参需非空且至少一个有效 id。 */
    void upsertBatchByContentIds(List<Long> contentIds);

    /** 直接删除 ES 文档；id 为空忽略，文档不存在幂等成功。 */
    void deleteDocumentByContentId(Long contentId);

    /** 关键词分页检索；高亮与 esSearchScore 在返回的文档上回填。 */
    Page<ContentDocument> searchContent(String keyword, Integer contentType, Integer current, Integer pageSize);

    /**
     * 全量重建：分页扫 MySQL 全表重写 content 索引，返回统计与是否完成。
     * 【现状】主代码没有 HTTP/Controller 入口，当前唯一调用方是
     * SearchReindexService 的委托实现，供管理端接入或编程式触发。
     */
    ReindexResult reindexAllFromMySql(int batchSize);
}
