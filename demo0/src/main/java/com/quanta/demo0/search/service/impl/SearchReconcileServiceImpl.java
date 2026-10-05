package com.quanta.demo0.search.service.impl;

import com.quanta.demo0.moderation.enums.ModerationTargetType;
import com.quanta.demo0.search.service.AnswerSearchService;
import com.quanta.demo0.search.service.ContentIndexService;
import com.quanta.demo0.search.service.SearchReconcileService;
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
 *
 * ============================================================
 * 【为什么消费时重新查 MySQL，而不是把"改成了什么"放进事件？】
 * ============================================================
 * 若事件携带最终状态（如"已删除"），一旦消费乱序——先收到"已删除"
 * 再收到早先的"点赞"——ES 就会被旧事件写回错误状态。改为每次对账
 * 都以 MySQL 实时快照为准：**事件只负责"提醒该看看这条数据了"，
 * 状态推导完全在消费时刻进行**，乱序、重复、丢失的增量最终都会被
 * 下一次对账覆盖修复，这正是"对账"区别于"指令"的关键。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchReconcileServiceImpl implements SearchReconcileService {

    private final ContentIndexService contentIndexService;
    private final AnswerSearchService answerSearchService;

    /**
     * 按 targetType 分发对账。抛出的 IllegalArgumentException 会被消费者
     * 计入重试并最终判死——非法类型不该静默吞掉，而要留死信线索。
     */
    @Override
    public void reconcileSearchIndex(String targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            throw new IllegalArgumentException("ES 校准缺少目标类型或目标 ID");
        }

        if (ModerationTargetType.CONTENT.name().equals(targetType)) {
            // 该方法会重新查询 MySQL，并自行决定执行 upsert 还是 delete。
            contentIndexService.upsertByContentId(targetId);
            log.info("帖子 ES 索引校准完成，contentId={}", targetId);
            return;
        }


        if (ModerationTargetType.ANSWER.name().equals(targetType)) {
            // 回答不存在、已删除或未审核通过时，底层方法会删除 ES 文档。
            answerSearchService.upsertByAnswerId(targetId);
            log.info("回答 ES 索引校准完成，answerId={}", targetId);
            return;
        }

        // 走到这里说明 targetType 未被识别：宁可失败进死信，也不能装作对账成功。
        throw new IllegalArgumentException("不支持的 ES 校准目标类型：" + targetType);
    }
}
