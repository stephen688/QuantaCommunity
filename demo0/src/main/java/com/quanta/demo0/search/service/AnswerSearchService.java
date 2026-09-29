package com.quanta.demo0.search.service;

import com.quanta.demo0.search.es.document.AnswerDocument;

import java.util.List;

/**
 * 搜索域回答索引与召回服务。
 *
 * <p>负责回答文档的可见性校验、索引写入和 RAG 召回，返回值使用搜索域文档。</p>
 */
public interface AnswerSearchService {

    void upsertByAnswerId(Long answerId);

    void deleteDocumentByAnswerId(Long answerId);

    List<AnswerDocument> searchAnswers(String keyword, int topK);
}
