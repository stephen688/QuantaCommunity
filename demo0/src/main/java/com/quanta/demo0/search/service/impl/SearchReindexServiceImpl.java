package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.search.result.ReindexResult;
import com.quanta.demo0.search.service.ContentIndexService;
import com.quanta.demo0.search.service.SearchReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 搜索域索引重建服务实现。
 *
 * <p>当前保留原有 content 全量重建入口；回答索引的批量重建能力后续可在 AnswerSearchService
 * 增加对应稳定契约后由此处统一协调。</p>
 *
 * ============================================================
 * 【为什么包一层委托，而不是让调用方直接找 ContentIndexService？】
 * ============================================================
 * 重建是"跨多个索引的编排动作"：今天只有 content，将来回答索引要重建时
 * 在这里追加协调即可 —— **调用方认 SearchReindexService 这一个门面**，
 * 不用知道背后有几个索引服务、各自的分批策略是什么。
 */
@Service
@RequiredArgsConstructor
public class SearchReindexServiceImpl implements SearchReindexService {

    private final ContentIndexService contentIndexService;

    /**
     * 委托内容索引服务执行 MySQL 到 ES 的全量重建。
     *
     * 分批、可见性分流、bulk 细节都在 ContentIndexServiceImpl.reindexAllFromMySql，
     * 这里不做任何加工；重建幂等可重跑（见该方法的"宁重不漏"注释）。
     */
    @Override
    public ReindexResult reindexAllFromMySql(int batchSize) {
        return contentIndexService.reindexAllFromMySql(batchSize);
    }
}
