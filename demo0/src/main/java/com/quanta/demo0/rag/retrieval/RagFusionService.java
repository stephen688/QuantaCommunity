package com.quanta.demo0.rag.retrieval;

import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 结果融合服务
 * ============================
 * 作用说明
 * ============================
 * 这个类负责将 ES 召回和向量召回的结果进行融合排序。
 * 在双路检索中的角色：
 * 双路检索 = ES 召回 + 向量召回 + 结果融合
 * ES 召回：关键词精准匹配（返回 source="ES" 的候选）
 * 向量召回：语义模糊匹配（返回 source="VECTOR" 的候选）
 * 结果融合：合并两路结果，去重，加权排序（返回 source="BOTH" 的候选）
 * ============================
 * 融合规则
 * ============================
 * finalScore = 0.6 * normalizedEsScore + 0.4 * normalizedVectorScore + sourceBonus
 * 其中：
 * normalizedEsScore   - ES 得分的 min-max 归一化值（0~1 之间）
 * normalizedVectorScore - 向量得分的 min-max 归一化值（0~1 之间）
 * sourceBonus         - 来源加分：两路都命中 +0.05，否则 +0
 * 归一化公式：
 * normalizedValue = (value - min) / (max - min)
 * 如果 max == min（只有一个值），返回 0.5
 * 如果某路未命中，归一化值按 0 计算
 * 排序规则：
 * 1. 按 finalScore 降序
 * 2. finalScore 相同时，按 contentId 降序（模拟 createTime 降序）
 * ============================
 * 执行流程（一步步拆解）
 * ============================
 * 第 1 步：处理空列表
 * 两路都为空 → 返回空列表
 * 某一路为空 → 初始化为空列表继续处理
 * * 第 2 步：按 contentId 分组
 * 使用 Map<Long, List<RagCandidate>> 按 contentId 分组
 * 同一个帖子的多个候选（ES 的 + 向量的）会分到同一组
 * 第 3 步：收集所有分数，计算归一化参数
 * 遍历 esCandidates 收集所有 esScore
 * 遍历 vectorCandidates 收集所有 vectorScore
 * 计算 esMin、esMax、vectorMin、vectorMax
 * 第 4 步：遍历每个分组，计算融合得分
 * 判断该帖子出现在哪路结果中（fromEs、fromVector）
 * 提取 esScore、vectorScore、title、contentSnippet
 * 对 esScore 做归一化 → normalizedEs
 * 对 vectorScore 做归一化 → normalizedVector
 * 计算 sourceBonus（两路都命中 +0.05）
 * 计算 finalScore = 0.6 * normalizedEs + 0.4 * normalizedVector + sourceBonus
 * 确定 source（BOTH / VECTOR / ES）
 * 第 5 步：排序
 * 按 finalScore 降序
 * finalScore 相同时按 contentId 降序
 * 第 6 步：截取 TopK
 * 返回前 topK 个候选
 * ============================
 * 异常处理策略
 * ============================
 * 融合过程不会抛异常（纯内存计算）
 * 如果某路结果为空，只融合另一路的结果
 */
@Slf4j
@Service
public class RagFusionService {

 @Autowired
    private RagProperties ragProperties;

    /**
     * 融合方法
     * ============================
     *
     * @param esCandidates     ES 召回的候选列表（可能为空）
     * @param vectorCandidates 向量召回的候选列表（可能为空）
     * @param finalTopK        最终返回的候选数量（Plan 默认 10）
     * @return List<RagCandidate> 融合排序后的候选列表
     * 每个候选包含：contentId、title、contentSnippet、fusionScore、source
     * source 可能是："ES"、"VECTOR"、"BOTH"
     */
    public List<RagCandidate> fuseCandidates(List<RagCandidate> esCandidates,
                                             List<RagCandidate> vectorCandidates,
                                             int finalTopK) {
        // 第 1 步：处理空列表
        if ((esCandidates == null || esCandidates.isEmpty())
                && (vectorCandidates == null || vectorCandidates.isEmpty())) {
            log.info("[RAG-FUSION] 两路召回结果均为空，返回空列表");
            return new ArrayList<>();
        }
        if (esCandidates == null) {
            esCandidates = new ArrayList<>();
        }
        if (vectorCandidates == null) {
            vectorCandidates = new ArrayList<>();
        }
// 第 2 步：按 contentId（问题 ID）分组
        Map<Long, List<RagCandidate>> grouped = new HashMap<>();

// 先把 ES 结果放入分组
        for (RagCandidate c : esCandidates) {
            grouped.computeIfAbsent(c.getContentId(), k -> new ArrayList<>()).add(c);
        }
// 再把向量结果放入分组
        for (RagCandidate c : vectorCandidates) {
            grouped.computeIfAbsent(c.getContentId(), k -> new ArrayList<>()).add(c);
        }
        // 第 3 步：收集所有分数，计算归一化参数
        List<Double> esScores = esCandidates.stream()
                .map(RagCandidate::getEsScore)
                .filter(score -> score != null)
                .collect(Collectors.toList());

        List<Double> vectorScores = vectorCandidates.stream()
                .map(RagCandidate::getVectorScore)
                .filter(score -> score != null)
                .collect(Collectors.toList());

        double esMin = esScores.isEmpty() ? 0 : esScores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double esMax = esScores.isEmpty() ? 0 : esScores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double vectorMin = vectorScores.isEmpty() ? 0 : vectorScores.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double vectorMax = vectorScores.isEmpty() ? 0 : vectorScores.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        log.info("[RAG-FUSION] 归一化参数: esMin={}, esMax={}, vectorMin={}, vectorMax={}",
                esMin, esMax, vectorMin, vectorMax);

        // 第 4 步：遍历每个分组，计算融合得分
        List<RagCandidate> fusedCandidates = new ArrayList<>();

        for (Map.Entry<Long, List<RagCandidate>> entry : grouped.entrySet()) {
            List<RagCandidate> candidates = entry.getValue();

            // 判断该问题出现在哪路结果中
            boolean fromEs = false;
            boolean fromVector = false;
            double maxEsScore = 0.0;
            double maxVectorScore = 0.0;
            String title = null;
            String docKind = null;
            Long contentId = entry.getKey();
            Long answerId = null;

            // 合并多个 snippet（帖子 + 多个回答）
            StringBuilder snippetBuilder = new StringBuilder();

            for (RagCandidate candidate : candidates) {
                if ("ES".equals(candidate.getSource()) || "BOTH".equals(candidate.getSource())) {
                    fromEs = true;
                    double currentEs = candidate.getEsScore() != null ? candidate.getEsScore() : 0.0;
                    maxEsScore = Math.max(maxEsScore, currentEs);  // 取最大值
                }
                if ("VECTOR".equals(candidate.getSource()) || "BOTH".equals(candidate.getSource())) {
                    fromVector = true;
                    double currentVector = candidate.getVectorScore() != null ? candidate.getVectorScore() : 0.0;
                    maxVectorScore = Math.max(maxVectorScore, currentVector);  // 取最大值
                }
                // 提取 title（优先取帖子标题）
                if (title == null && candidate.getTitle() != null) {
                    title = candidate.getTitle();
                }
                // 提取 docKind 和 answerId
                if (docKind == null) {
                    docKind = candidate.getDocKind();
                }
                if (candidate.getAnswerId() != null) {
                    answerId = candidate.getAnswerId();
                }
                // 合并 snippet
                if (candidate.getContentSnippet() != null) {
                    if (snippetBuilder.length() > 0) {
                        snippetBuilder.append("\n---\n");
                    }
                    snippetBuilder.append(candidate.getContentSnippet());
                }
            }

            // 限制 snippet 总长度（最多 500 字）
            String contentSnippet = snippetBuilder.length() > 500
                    ? snippetBuilder.substring(0, 500)
                    : snippetBuilder.toString();

            // 归一化 ES 得分
            double normalizedEs = fromEs ? normalize(maxEsScore, esMin, esMax) : 0.0;

            // 归一化向量得分
            double normalizedVector = fromVector ? normalize(maxVectorScore, vectorMin, vectorMax) : 0.0;

            // 来源加分：两路都命中 +0.05
            double sourceBonus = (fromEs && fromVector) ? ragProperties.getSourceBonus() : 0.0;

            // 计算融合得分
            double fusionScore = ragProperties.getEsWeight() * normalizedEs
                    + ragProperties.getVectorWeight() * normalizedVector
                    + sourceBonus;

            // 确定来源
            String source;
            if (fromEs && fromVector) {
                source = "BOTH";
            } else if (fromVector) {
                source = "VECTOR";
            } else {
                source = "ES";
            }

            // 创建融合后的候选对象
            RagCandidate fused = RagCandidate.builder()
                    .docKind(docKind)
                    .contentId(contentId)
                    .answerId(answerId)
                    .title(title)
                    .contentSnippet(contentSnippet)
                    .esScore(fromEs ? maxEsScore : null)
                    .vectorScore(fromVector ? maxVectorScore : null)
                    .fusionScore(fusionScore)
                    .source(source)
                    .build();
            fusedCandidates.add(fused);
        }

        // 第 5 步：排序
        // 按 finalScore 降序，finalScore 相同时按 contentId 降序
        fusedCandidates.sort((c1, c2) -> {
            int scoreCompare = Double.compare(c2.getFusionScore(), c1.getFusionScore());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            // finalScore 相同，按 contentId 降序
            return Long.compare(c2.getContentId(), c1.getContentId());
        });
        // 第 6 步：截取 TopK
        if (fusedCandidates.size() > finalTopK) {
            fusedCandidates = fusedCandidates.subList(0, finalTopK);
            log.info("[RAG-FUSION] 融合后候选数量 {} 超过 finalTopK {}，截取前 {} 条",
                    fusedCandidates.size() + (fusedCandidates.size() > finalTopK ? fusedCandidates.size() - finalTopK : 0), finalTopK, finalTopK);
        } else {
            log.info("[RAG-FUSION] 融合后候选数量 {} 小于 finalTopK {}，返回全部",
                    fusedCandidates.size(), finalTopK);
        }

        return fusedCandidates;
    }

    /**
     * min-max 归一化
     * <p>
     * ============================
     *
     * @param value 原始值
     * @param min   最小值
     * @param max   最大值
     * @return 归一化后的值（0~1 之间）
     */
    private double normalize(double value, double min, double max) {
        if (max == min) {
            return 0.5; // 只有一个值时返回中间值
        }
        return (value - min) / (max - min);
    }

}