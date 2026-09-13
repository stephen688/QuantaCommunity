package com.quanta.demo0.rag.retrieval;

import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagCandidate;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量召回服务
 * ============================
 * 作用说明
 * ============================
 * 这个类负责用向量库（SimpleVectorStore）执行语义检索，召回 Top20 相关帖子。
 * 在双路检索中的角色：
 * 双路检索 = ES 召回 + 向量召回
 * ES 召回负责"关键词精准匹配"（用户搜"考研"，ES 找标题/正文含"考研"的帖子）
 * 向量召回负责"语义模糊匹配"（用户搜"考研准备"，向量找语义相关但没出现"考研"二字的帖子）
 * 工作原理：
 * 1. 用户输入 query → Embedding 模型转为 1536 维向量
 * 2. 在向量库中搜索与该向量最相似的 Document
 * 3. 返回相似度最高的 Top20 个 Document
 * 4. 从 Document 的 metadata 中提取 contentId、title 等信息
 * 5. 填充到 RagCandidate 对象中
 * ============================
 * 执行流程（一步步拆解）
 * ============================
 * 第 1 步：参数校验
 * query 不能为空，为空直接返回空列表
 * 第 2 步：构建向量搜索请求
 * 使用 SearchRequest.builder() 构建：
 * query: 用户输入的原始查询文本（会自动向量化）
 * topK: 召回数量（默认用 ragProperties.vectorTopK）
 * filterExpression: contentType 过滤表达式（如果传了的话）
 * 第 3 步：执行向量搜索
 * 调用 vectorStore.similaritySearch(searchRequest)
 * 内部流程：
 * a. Embedding 模型将 query 转为向量
 * b. 计算 query 向量与库中所有向量的余弦相似度
 * c. 按相似度降序排序，取 TopK
 * 第 4 步：解析向量搜索结果
 * 遍历 Document 列表，提取：
 * contentId: 从 metadata 中获取
 * title: 从 metadata 中获取（如果没有则从 text 中截取）
 * contentSnippet: 从 Document.text 中截取前 200 字
 * vectorScore: 相似度得分（Document.score）
 * 填充到 RagCandidate 对象中
 * 第 5 步：返回结果
 * 成功 → 返回 RagCandidate 列表
 * 失败 → 返回空列表（不抛异常，避免影响主流程）
 * ============================
 * 异常处理策略
 * ============================
 * 向量搜索失败时返回空列表，不抛异常
 * 原因：
 * 向量召回只是双路检索的一路，失败不影响 ES 召回
 * 最坏情况：只有 ES 召回的结果，搜索体验稍差但功能可用
 */

@Service
@Slf4j
public class VectorRecallService {

    @Autowired
    private RagProperties ragProperties;
    @Autowired
    private SimpleVectorStore vectorStore;

    /**
     * 向量召回方法
     * <p>
     * ============================
     *
     * @param query       搜索关键词（用户输入的原始查询）
     * @param contentType 内容类型过滤：1-生活区 2-专业区 null-全部
     * @param topK        召回数量（默认用 ragProperties.vectorTopK）
     * @return List<RagCandidate> 向量召回的候选帖子列表
     * 每个 RagCandidate 包含：contentId、title、contentSnippet、vectorScore、source="VECTOR"
     * 如果向量搜索失败或无结果，返回空列表
     */
    public List<RagCandidate> recall(String query, Integer contentType, int topK) {
        //1. 参数校验
        if (query == null || query.trim().isEmpty()) {
            log.warn(" query 不能为空");
            return List.of();
        }
        if ( topK <= 0) {
            topK = ragProperties.getVectorTopK(); // 默认召回数量
        }

        //2. 构建向量搜索请求
        try {

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query) // 用户输入的原始查询文本，SimpleVectorStore 内部会自动向量化
                    .topK(topK)// 召回数量
                    .filterExpression(contentType != null ? "contentType ==" + contentType : null) // contentType 过滤表达式
                    .build();

            //3. 执行向量搜索
            List<Document> documents = vectorStore.similaritySearch(searchRequest);

            //4. 解析向量搜索结果
            List<RagCandidate> candidates = new ArrayList<>();

            for (Document doc : documents) {
                Map<String, Object> metadata = doc.getMetadata();

                // 提取 docKind（POST 或 ANSWER）
                String docKind = (String) metadata.get("docKind");
                if (docKind == null) {
                    docKind = "POST"; // 兼容旧数据
                }

                // 根据 docKind 提取对应 ID
                Long contentId = null;
                Long answerId = null;

                if ("ANSWER".equals(docKind)) {
                    Object answerIdObj = metadata.get("answerId");
                    if (answerIdObj == null) {
                        continue; // 跳过无效文档
                    }
                    answerId = ((Number) answerIdObj).longValue();
                    contentId = ((Number) metadata.get("contentId")).longValue(); // contentId 就是问题ID
                } else {
                    Object contentIdObj = metadata.get("contentId");
                    if (contentIdObj == null) {
                        continue; // 跳过无效文档
                    }
                    contentId = ((Number) contentIdObj).longValue();
                }
                // 提取 title（优先从 metadata 取，没有则从 text 截取第一行）
                String title = (String) metadata.get("title");
                if (title == null && doc.getText() != null) {
                    int newlineIndex = doc.getText().indexOf("\n");
                    title = newlineIndex > 0 ? doc.getText().substring(0, newlineIndex) : doc.getText();
                }

                String contentSnippet;
                if ("ANSWER".equals(docKind)) {
                    // 回答文档：优先从 metadata 取 answerSnippet，没有则从 text 中找回答段
                    String answerSnippet = (String) metadata.get("answerSnippet");
                    if (answerSnippet != null && !answerSnippet.isEmpty()) {
                        contentSnippet = answerSnippet.length() > 200
                                ? answerSnippet.substring(0, 200)
                                : answerSnippet;
                    } else {
                        // 兼容：从 text 中截取后200字（更可能是回答部分）
                        String text = doc.getText();
                        contentSnippet = text != null && text.length() > 200
                                ? text.substring(text.length() - 200)
                                : text;
                    }
                } else {
                    // 帖子文档：截取前200字
                    String text = doc.getText();
                    contentSnippet = text != null && text.length() > 200
                            ? text.substring(0, 200)
                            : text;
                }

                // 提取 vectorScore（相似度得分）
                double vectorScore = doc.getScore() != null ? doc.getScore() : 0.0;

                // 构建 RagCandidate 对象
                RagCandidate candidate = RagCandidate.builder()
                        .docKind(docKind)
                        .contentId(contentId)
                        .answerId(answerId)
                        .title(title)
                        .contentSnippet(contentSnippet)
                        .vectorScore(vectorScore)
                        .source("VECTOR")
                        .build();

                candidates.add(candidate);
            }

            // chunk 聚合：按 (docKind, contentId, answerId) 分组，每组保留 vectorScore 最大的一条
            List<RagCandidate> aggregated = aggregateChunks(candidates);
            log.info("[VECTOR-RECALL] chunk 聚合: 原始={}, 聚合后={}", candidates.size(), aggregated.size());
            return aggregated;

        } catch (Exception e) {
            log.error("向量召回失败，返回空列表", e);
            return List.of();
        }

    }
    /**
     * 聚合 chunk 候选
     * ============================
     * 分组键：
     *   - POST: (POST, contentId, null)
     *   - ANSWER: (ANSWER, contentId, answerId)
     * 聚合规则：每组保留 vectorScore 最大的一条
     */
    private List<RagCandidate> aggregateChunks(List<RagCandidate> candidates) {
        Map<String, RagCandidate> groupMap = new LinkedHashMap<>();

        for (RagCandidate candidate : candidates) {
            // 构建分组键
            String groupKey = buildGroupKey(candidate);

            // 保留 score 最大的
            RagCandidate existing = groupMap.get(groupKey);
            if (existing == null || candidate.getVectorScore() > existing.getVectorScore()) {
                groupMap.put(groupKey, candidate);
            }
        }

        // 按原始顺序返回（LinkedHashMap 保持插入顺序）
        return new ArrayList<>(groupMap.values());
    }

    /**
     * 构建分组键
     */
    private String buildGroupKey(RagCandidate candidate) {
        String docKind = candidate.getDocKind() != null ? candidate.getDocKind() : "POST";
        Long contentId = candidate.getContentId();
        Long answerId = candidate.getAnswerId();

        // answerId null 用 0 区分
        String answerIdStr = answerId == null ? "0" : answerId.toString();

        return docKind + "|" + contentId + "|" + answerIdStr;
    }
}
