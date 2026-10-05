package com.quanta.demo0.content.service;

import com.quanta.demo0.content.vo.BotSyncPageVO;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;

/** bot 内容源同步与政策文档管理（C-3）。 */
// 【给谁用】QuantaBot（外部 AI 助手）按水位线拉取社区"三源"内容（帖子/回答/政策文档）喂知识库。
// 【同步语义】update_time >= since 增量、**宁重不漏**——少推一条删失就漏删，
// 重复由下游按 docId 覆盖，代价为零（数据口径详见 BotContentSyncMapper 的类注释）。
public interface BotContentSyncService {

    /**
     * 增量同步查询：返回自水位线以来有变更的三源文档（含删除事件）。
     * 【契约】since 支持 "yyyy-MM-dd" 或 "yyyy-MM-dd HH:mm:ss"，缺失/非法抛 ContentFailedException；
     * pageSize 服务端截断到上限 200；hasMore 由总数与页码推算。
     * 【宁重不漏】水位线推进期间的边界行会被重复带出，调用方按 docId 幂等覆盖即可。
     */
    BotSyncPageVO getSync(String since, int pageNum, int pageSize);

    /**
     * 新增或更新政策文档（按 docId 幂等 upsert）。
     * 【坑】已软删的 docId 再次 upsert 会被**复活**（is_deleted 重置为 0）——
     * "更新"与"复活"是同一条路径，调用方要知道这个副作用。
     */
    void upsertPolicyDoc(BotPolicyDocDTO dto);

    /**
     * 软删政策文档（墓碑式：is_deleted=1，行保留）。
     * 【为什么软删】删除要作为事件被水位线同步带出去、通知 bot 收走知识——
     * 物理删除会让下游永远留着幽灵文档。
     */
    void softDeletePolicyDoc(String docId);
}
