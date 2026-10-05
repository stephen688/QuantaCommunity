package com.quanta.demo0.content.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 政策文档实体（C-3 POLICY 内容源）。 */

/**
 * 映射 tb_bot_policy_doc 表：QuantaBot 问答用的政策类知识（社区规则等），
 * 与帖子（POST）、回答（ANSWER）并列为"三源同步"的第三源 —— 由
 * BotContentSyncMapper.selectSyncBatch 以 docKind='POLICY'、docId=doc_id
 * 并入统一的同步文档流，喂给 QuantaBot 的 RAG。
 *
 * ============================================================
 * 【为什么政策文档不走 tb_content，要单独一张表？】
 * ============================================================
 * 帖子的生命周期绑定"用户发布 → AI 审核 → 可见性"这套状态机，还挂着互动计数；
 * 政策文档是运营维护的静态知识：没有作者、没有审核流、没有计数，
 * 变更方式只有"按业务键 docId 做 upsert + 软删"（见
 * BotContentSyncServiceImpl.upsertPolicyDoc / softDeletePolicyDoc）。
 * 硬塞进 tb_content 会逼着所有可见性 SQL 带上一堆对它无意义的条件。
 * **表结构跟着生命周期走：不同生命周期的数据不共用一张主表**。
 *
 * 【docId 为什么是 String 而不是自增 id？】
 * docId 是跨系统同步契约的一部分（QuantaBot 侧按 docId 去重/更新/移除），
 * 由调用方提供、跨环境稳定；自增 id 只在本库有意义，换库就会漂移。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotPolicyDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键，仅本库内部使用；跨系统标识一律用 docId。 */
    private Long id;

    /** 业务唯一键（同步契约）。查询、upsert、软删都以它定位。 */
    private String docId;

    /** 文档标题；同步 SQL 里 IFNULL 兜成空串。 */
    private String title;

    /** 文档正文，QuantaBot RAG 检索召回的内容本体。 */
    private String content;

    /**
     * 软删标识：updatePolicyDocByDocId 顺带复活（置 0），softDeletePolicyDocByDocId 置 1；
     * 同步链路据此把"删除"作为事件传播给 QuantaBot（status='deleted'）。
     */
    private Integer isDeleted;

    /** 首次插入时间（insertPolicyDoc 只写业务列，时间由表侧维护）。 */
    private LocalDateTime createTime;

    /**
     * 变更时间，SQL 里用 now() 维护；也是增量同步的水位线
     * （selectSyncBatch / countSync 都按 update_time >= since 拉取）。
     */
    private LocalDateTime updateTime;
}
