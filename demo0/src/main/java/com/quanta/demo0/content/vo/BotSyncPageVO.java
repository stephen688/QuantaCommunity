package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** 内容同步分页响应。 */
/**
 * ============================================================
 * 【items + hasMore：给机器消费的分页，只要"还有没有"】
 * ============================================================
 * 没有 total/pageNum/totalPage —— Bot 不渲染分页 UI，循环逻辑就是
 * "拉一页 → 按 docId 幂等 upsert → hasMore 为真再来一页"。
 * 给机器的契约里每个字段都要有消费方，**没人读的字段不进契约**
 * （total 还要多一次 count 查询，能不给就不给）。
 *
 * 【为什么这里用页码分页而用户侧 Feed 用游标】
 * 消费方是程序、数据是 "update_time >= since" 的稳定小增量集、
 * 消化端按 docId 幂等 —— 页码偏移的重复/漂移无害，简单性优先。
 * 与 ScrollResult（游标）的取舍对比见 ContentController.recommend 的说明。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotSyncPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本页同步文档（三源合一，见 BotSyncDocVO） */
    private List<BotSyncDocVO> items;

    /**
     * 是否还有下一页（page * size &lt; total 判断，见 BotContentSyncServiceImpl.getSync）
     * 【坑】count 与 select 是两次查询，并发写入下 hasMore 边界可能偏差一页 ——
     * 宁重不漏语义下无害（多拉一页空数据/重复数据，Bot 侧幂等消化）。
     */
    private boolean hasMore;
}
