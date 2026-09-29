package com.quanta.demo0.search.service;

import com.quanta.demo0.search.result.ReindexResult;

/**
 * 搜索域索引重建服务。
 *
 * <p>统一协调各索引服务的全量重建入口，避免调用方直接依赖具体文档 Mapper。</p>
 */
public interface SearchReindexService {

    ReindexResult reindexAllFromMySql(int batchSize);
}
