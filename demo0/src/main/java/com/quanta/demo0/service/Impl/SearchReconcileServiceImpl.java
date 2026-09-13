package com.quanta.demo0.service.Impl;

import com.quanta.demo0.annotation.ModerationTargetType;
import com.quanta.demo0.es.service.ElasticSearchService;
import com.quanta.demo0.service.SearchReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Elasticsearch 索引校准服务实现类。
 *
 * 设计原则：
 * 1. 事件只提供目标类型和目标 ID；
 * 2. 最终状态始终以 MySQL 当前数据为准；
 * 3. 重复执行使用相同文档 ID 覆盖或删除，保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchReconcileServiceImpl implements SearchReconcileService {

    private final ElasticSearchService elasticSearchService;

    @Override
    public void reconcileSearchIndex(String targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            throw new IllegalArgumentException("ES 校准缺少目标类型或目标 ID");
        }

        if (ModerationTargetType.CONTENT.name().equals(targetType)) {
            // 该方法会重新查询 MySQL，并自行决定执行 upsert 还是 delete。
            elasticSearchService.upsertByContentId(targetId);
            log.info("帖子 ES 索引校准完成，contentId={}", targetId);
            return;
        }


        if (ModerationTargetType.ANSWER.name().equals(targetType)) {
            // 回答不存在、已删除或未审核通过时，底层方法会删除 ES 文档。
            elasticSearchService.upsertByAnswerId(targetId);
            log.info("回答 ES 索引校准完成，answerId={}", targetId);
            return;
        }

        throw new IllegalArgumentException("不支持的 ES 校准目标类型：" + targetType);
    }
}