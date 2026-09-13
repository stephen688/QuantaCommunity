package com.quanta.demo0.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RAG 候选帖子对象（中间态）
 * 作用：在双路检索（ES + 向量）后、融合排序前，暂存每个帖子的检索信息
 * 生命周期：
 *   1. ES 召回阶段：填充 contentId、title、contentSnippet、esScore，source 标记为 ES
 *   2. 向量召回阶段：填充 contentId、title、contentSnippet、vectorScore，source 标记为 VECTOR
 *   3. 融合阶段：同一 contentId 的两条记录合并为一条，source 改为 BOTH，计算 fusionScore
 *   4. 排序截断：按 fusionScore 降序排序，取 Top10
 * 字段说明：
 *   contentId      - 帖子 ID，用于去重和后续查详情
 *   title          - 帖子标题，用于展示
 *   contentSnippet - 帖子正文摘要，用于 AI Prompt 引用
 *   source         - 命中来源：ES / VECTOR / BOTH
 *   esScore        - ES 检索得分（仅 ES 或 BOTH 命中时有值）
 *   vectorScore    - 向量相似度得分（仅 VECTOR 或 BOTH 命中时有值）
 *   fusionScore    - 融合后的最终得分，用于排序
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagCandidate implements Serializable {
    /**
     * 文档类型标识
     * POST   - 帖子文档
     * ANSWER - 回答文档
     * 用于区分候选来源，融合时按 docKind + ID 去重
     */
    private String docKind;

    /**
     * 回答 ID（仅 docKind=ANSWER 时有值，POST 为 null）
     * 用于后续查回答详情
     */
    private Long answerId;
    /**
     * 问题/帖子 ID
     * 帖子类：填 contentId
     * 回答类：填 questionId（所属问题 ID）
     * 用于与帖子候选在同一问题维度上融合
     */
    private Long contentId;

    /**
     * 帖子标题
     * 用于前端展示和 AI Prompt 上下文引用
     */
    private String title;

    /**
     * 帖子正文摘要
     * 用于 AI Prompt 上下文引用
     * 注意：不传全文，只传摘要，避免 token 过高
     */
    private String contentSnippet;

    /**
     * 命中来源
     * ES    - 仅 ES 关键词检索命中
     * VECTOR - 仅向量语义检索命中
     * BOTH   - 两路都命中（相关性最高）
     */
    private String source;

    /**
     * ES 检索得分
     * 仅当 source 为 ES 或 BOTH 时有值
     * 值越大表示关键词匹配度越高
     */
    private Double esScore;

    /**
     * 向量相似度得分
     * 仅当 source 为 VECTOR 或 BOTH 时有值
     * 值越大表示语义相似度越高
     */
    private Double vectorScore;

    /**
     * 融合后的最终得分
     * 计算公式：fusionScore = esWeight * esNorm + vectorWeight * vectorNorm
     * 用于最终排序，值越大越靠前
     */
    private Double fusionScore;
}