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
 */
public interface ContentIndexService {

    void upsertByContentId(Long contentId);

    void upsertBatchByContentIds(List<Long> contentIds);

    void deleteDocumentByContentId(Long contentId);

    Page<ContentDocument> searchContent(String keyword, Integer contentType, Integer current, Integer pageSize);

    ReindexResult reindexAllFromMySql(int batchSize);
}
