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
 */
@Service
@RequiredArgsConstructor
public class SearchReindexServiceImpl implements SearchReindexService {

    private final ContentIndexService contentIndexService;

    /**
     * 委托内容索引服务执行 MySQL 到 ES 的全量重建。
     */
    @Override
    public ReindexResult reindexAllFromMySql(int batchSize) {
        return contentIndexService.reindexAllFromMySql(batchSize);
    }
}
