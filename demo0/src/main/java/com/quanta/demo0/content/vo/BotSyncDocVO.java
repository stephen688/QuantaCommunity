package com.quanta.demo0.content.vo;

import lombok.Data;

import java.io.Serializable;

/** 与 QuantaBot SyncDoc 契约一致的同步文档。 */
/**
 * ============================================================
 * 【字段名 = 对外 JSON 契约：冻结、冗余、不改名】
 * ============================================================
 * Bot 端按这里的字段名反序列化 —— 一旦改名/删字段，已部署的 Bot 版本直接解析失败。
 * 最典型的是 **updateTime 和 updatedAt 并存且值相同**（见 BotContentSyncMapper.xml
 * 三源 UNION 里两列都取 update_time）：这是契约演进留下的兼容层，
 * 看着冗余也不能删 —— **对外契约的字段宁可难看，不能破坏**。
 *
 * 【三源合一的形状】
 * 这个 VO 由一条 UNION ALL SQL 装配出三种 docKind（见 BotContentSyncMapper.xml）：
 *   POST    —— tb_content（docId 前缀 "content:"，answerId 为 NULL）
 *   ANSWER  —— tb_question_answer（docId 前缀 "answer:"，title 取父帖标题）
 *   POLICY  —— tb_bot_policy_doc（docId 不带前缀，由 BotPolicyDocDTO 直接指定）
 * docId 带类型前缀是为了跨源全局唯一 —— Bot 把它当知识库的 upsert 主键。
 *
 * 【status=deleted：删除靠同步通道传播】
 * 服务端删帖/删文档只做软删 + 刷新 update_time，这条"墓碑"就会出现在
 * 下一次 sync 结果里，Bot 据 status=deleted 清理本地知识 ——
 * **变更（含删除）只有一条传播路径**，不需要额外的删除推送。
 *
 * 【时间是格式化字符串，不是时间戳】
 * createTime/updateTime/updatedAt 固定 "yyyy-MM-dd HH:mm:ss" —— Bot 拿
 * 上一批最后一条的 updateTime 当下一轮的 since 水位线，两端按同一格式解析。
 */
@Data
public class BotSyncDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全局唯一文档键（"content:123" / "answer:456" / 政策文档原生 docId） */
    private String docId;

    /** 文档种类：POST / ANSWER / POLICY（Bot 按它路由不同的知识入库逻辑） */
    private String docKind;

    /** 帖子源 ID（回答文档里是所属问题帖 ID；POLICY 源为 NULL） */
    private Long contentId;

    /** 回答源 ID（POST/POLICY 源为 NULL） */
    private Long answerId;

    /** 标题（回答文档取父帖标题，保证 Bot 侧文档自含上下文） */
    private String title;

    /** 正文内容 */
    private String content;

    /** 创建时间（SQL 层 DATE_FORMAT 成字符串，格式契约见类注释） */
    private String createTime;

    /** 最后更新时间 —— Bot 的同步水位线来源 */
    private String updateTime;

    /** 与 updateTime 同值的兼容字段（历史契约遗留，不可删除） */
    private String updatedAt;

    /** 可见性：active / deleted（墓碑，Bot 收到后删除本地对应知识） */
    private String status;
}
