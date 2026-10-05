package com.quanta.demo0.search.service;

import com.quanta.demo0.search.es.document.AnswerDocument;

import java.util.List;

/**
 * 搜索域回答索引与召回服务。
 *
 * <p>负责回答文档的可见性校验、索引写入和 RAG 召回，返回值使用搜索域文档。</p>
 */
public interface AnswerSearchService {

    /**
     * 按 MySQL 当前快照写入或删除 ES 文档。
     *
     * <p>【语义】"可见才写"：回答自身与父问题任一不满足 is_deleted=0 且
     * audit_status=1，都会把已存在的文档从 ES 移除（而不是跳过）；
     * 写入失败抛 SearchFailedException，交由上游重试/对账。</p>
     */
    void upsertByAnswerId(Long answerId);

    /** 删除 ES 回答文档；文档不存在按幂等成功处理（Mapper 内部消化 404）。 */
    void deleteDocumentByAnswerId(Long answerId);

    /**
     * 按相关性召回最相关的 topK 条回答文档（RAG 检索用）。
     *
     * <p>【降级】关键词为空或 ES 异常时返回空列表而不抛异常——
     * 召回失败不应拖垮 RAG 主链路；调用方（rag 包 EsRecallService）据此继续其余召回源。
     * 文档的 esSearchScore 字段仅在检索时回填，供后续融合排序。</p>
     */
    List<AnswerDocument> searchAnswers(String keyword, int topK);
}
