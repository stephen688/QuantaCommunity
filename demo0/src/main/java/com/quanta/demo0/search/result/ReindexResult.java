package com.quanta.demo0.search.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全量重建（MySQL → ES）的统计结果。
 *
 * ============================================================
 * 【怎么读这份统计：completed=false 不代表要"回滚"】
 * ============================================================
 * 重建按批推进，某批异常时整批计入 failure 并停止，之前批次已写入的
 * 数据保持有效 —— 因为 ES 文档 _id=contentId，**重跑是覆盖写、删除幂等**，
 * 看到 completed=false 直接再调一次即可，没有"清一半脏数据"的风险。
 * failure 有两种口径：bulk 单条错误逐条计数；批次级异常整批计入
 * （见 ContentIndexServiceImpl.reindexAllFromMySql）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReindexResult {
    private long total;      // MySQL 扫描总数
    private long success;    // ES 写入成功数
    private long failure;     // ES 写入失败数
    private boolean completed;    // 是否完成（true表示已完成，false 表示未完成）
}
