package com.quanta.demo0.content.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 政策文档 upsert 请求。 */
/**
 * 入参说明：docId/title/content 三个字段全部必填，由运营管理员经
 * POST /bot/knowledge/policy-docs 提交（详细契约见下）。
 *
 * ============================================================
 * 【docId 是调用方给的业务主键，不是自增 id —— upsert 幂等的锚点】
 * ============================================================
 * 政策文档由外部脚本/运营工具按稳定 ID（如 "policy:refund"）提交，
 * 同一 docId 重复提交 = 覆盖更新（顺带复活软删文档，见 BotContentSyncServiceImpl.upsertPolicyDoc）。
 * 这让"导入脚本重跑"天然安全 —— **幂等写的关键是把幂等键交给调用方**，
 * 服务端生成的自增 id 做不到这一点。
 *
 * 【只有三个字段：docId/title/content —— 没有生命周期字段】
 * 没有 status/isDeleted：删除走专门的 DELETE 接口（服务端软删墓碑），
 * 客户端不能通过 upsert 直接改状态 —— upsert 只管"内容是什么"，
 * "它存在与否"由服务端的状态机管。
 *
 * 【校验在 Service 不在注解】docId≤64 字符、title≤200、content 必填
 * 写在 BotContentSyncServiceImpl.validatePolicyDoc —— 与本包其它 DTO 的同一取舍：
 * 校验离写路径最近，其它入口无法绕过。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BotPolicyDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务主键（调用方指定的稳定 ID，≤64 字符）
     * 【坑】Bot 同步链路里它直接成为 SyncDoc 的 docId（POLICY 源不带类型前缀），
     * 改一个 docId = Bot 知识库里一条旧文档失效 + 一条新文档生效。
     */
    private String docId;

    /**
     * 文档标题（必填，≤200 字符）
     */
    private String title;

    /**
     * 文档正文（必填，长度不设上限）
     * 这是 Bot RAG 检索的原始语料 —— 内容质量直接决定 Bot 回答口径，所以写入权限收敛到运营。
     */
    private String content;
}
