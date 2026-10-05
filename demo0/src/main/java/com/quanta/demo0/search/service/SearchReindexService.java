package com.quanta.demo0.search.service;

import com.quanta.demo0.search.result.ReindexResult;

/**
 * 搜索域索引重建服务。
 *
 * <p>统一协调各索引服务的全量重建入口，避免调用方直接依赖具体文档 Mapper。</p>
 */
public interface SearchReindexService {

    /**
     * 触发 MySQL → ES 的全量重建（当前覆盖 content 索引）。
     *
     * @param batchSize 每批从 MySQL 捞取的行数；<=0 时实现回退默认值 1000
     * @return 统计结果；completed=false 表示中途失败，可直接重跑（幂等）
     */
    ReindexResult reindexAllFromMySql(int batchSize);
}
