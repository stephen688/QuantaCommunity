package com.quanta.demo0.es.service;

import com.github.pagehelper.Page;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.es.document.AnswerDocument;
import com.quanta.demo0.result.ReindexResult;
import com.quanta.demo0.vo.ContentVO;
import com.quanta.demo0.vo.PageVO;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

public interface ElasticSearchService {

    // 单条 upsert（新增或更新）
    void upsertByContentId(Long contentId) ;

    // 批量 upsert
    void upsertBatchByContentIds(List<Long> contentIds);

    // 从索引删除（或按你的策略改成写 isDeleted=1）
    void deleteDocumentByContentId(Long contentId);

    // 全量重建索引（先同步实现，后续可改异步任务）
    //目的是将 MySQL 中的所有内容数据重新索引到 ES 中，确保 ES 索引与 MySQL 数据完全一致。
    ReindexResult reindexAllFromMySql(int batchSize);

    /**
     * 搜索内容（ES 查询）
     *
     * @param keyword     关键词
     * @param contentType 内容类型（可选）
     * @param current     当前页
     * @param pageSize    每页大小
     * @return 分页结果
     */
    Page<Content> searchContent(String keyword, Integer contentType, Integer current, Integer pageSize);

    /**
     * 根据 answerId 执行 upsert（新增或更新）
     */
    void upsertByAnswerId(Long answerId);

    /**
     * 根据 answerId 从 ES 索引删除回答文档
     */
    void deleteDocumentByAnswerId(Long answerId);

    /**
     * 搜索回答（ES 查询，供 RAG 检索使用）
     *
     * @param keyword 关键词
     * @param topK    返回数量
     * @return 回答列表
     */
    List<AnswerDocument> searchAnswers(String keyword, int topK);
}
