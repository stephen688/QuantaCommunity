package com.quanta.demo0.rag.retrieval;


import com.quanta.demo0.exception.RagRetrieveException;
import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;


/**
 * RAG 检索门面服务
 * ============================
 * 作用说明
 * ============================
 * 这个类是双路检索的"总调度器"，负责并行执行 ES 召回和向量召回，
 * 然后调用融合服务输出最终结果。
 * 在 RAG 架构中的角色：
 *   用户搜索请求 → RagRetrieveFacade（并行调度） → 返回融合后的候选列表
 *   内部流程：
 *     1. 用 CompletableFuture 并行执行 ES 召回和向量召回
 *     2. 单路异常仅记录日志，返回空列表（失败隔离）
 *     3. 两路都失败时抛出可识别业务异常，供上层降级
 *     4. 调用 RagFusionService 融合两路结果
 * ===========================
 * 执行流程（一步步拆解）
 * ===========================
 * 第 1 步：接收检索请求
 *   参数：query（搜索关键词）、contentType（内容类型）
 *   topK 从 ragProperties 中获取
 * 第 2 步：并行执行双路召回
 *   使用 CompletableFuture.supplyAsync() 并行执行：
 *     esFuture: 调用 esRecallService.recall()
 *     vectorFuture: 调用 vectorRecallService.recall()
 *   注意：两路召回互不影响，即使一路失败也不影响另一路
 * 第 3 步：等待两路召回完成
 *   使用 CompletableFuture.allOf(esFuture, vectorFuture).join()
 *   阻塞等待两路都完成（或失败）
 * 第 4 步：获取召回结果
 *   esCandidates = esFuture.get()（失败返回空列表）
 *   vectorCandidates = vectorFuture.get()（失败返回空列表）
 * 第 5 步：检查两路是否都失败
 *   如果两路都为空，抛出 RagRetrieveException（供上层降级）
 * 第 6 步：结果融合
 *   调用 ragFusionService.fuseCandidates(esCandidates, vectorCandidates, finalTopK)
 *   返回融合后的候选列表
 * ============================
 * 异常处理策略
 * ============================
 * 每一路召回都有独立的 try-catch，失败返回空列表
 * 两路都失败时抛出 RagRetrieveException，供上层降级处理
 * 融合过程不会抛异常（纯内存计算）
 */



@Slf4j
@Service
public class RagRetrieveFacade {

    @Autowired
    private EsRecallService esRecallService;
    @Autowired
    private VectorRecallService vectorRecallService;
    @Autowired
    private RagFusionService ragFusionService;
    @Autowired
    private RagProperties ragProperties;


    /**
     * 并行检索并融合结果
     * ============================
     * @param query       搜索关键词（用户输入的原始查询）
     * @param contentType 内容类型过滤：1-生活区 2-专业区 null-全部
     * @return List<RagCandidate> 融合排序后的候选列表
     *   每个候选包含：contentId、title、contentSnippet、fusionScore、source
     *   source 可能是："ES"、"VECTOR"、"BOTH"
     *   如果两路召回都失败，抛出 RagRetrieveException
     */

    public List<RagCandidate> retrieveAndFuse(String query, Integer contentType) {
        log.info("[RAG-FACADE] 开始并行检索: query={}, contentType={}", query, contentType);

        // 从配置中获取 topK
        int esTopK = ragProperties.getEsTopK();
        int vectorTopK = ragProperties.getVectorTopK();
        int finalTopK = ragProperties.getFinalTopK();

        // 第 2 步：并行执行双路召回
        // ES 召回（关键词精准匹配）
        CompletableFuture<List<RagCandidate>> esFuture = CompletableFuture.supplyAsync(() -> {
            try {
                List<RagCandidate> candidates = esRecallService.recall(query, contentType, esTopK);
                log.info("[RAG-FACADE] ES 召回完成: {} 条", candidates != null ? candidates.size() : 0);
                return candidates != null ? candidates : new ArrayList<>();
            } catch (Exception e) {
                log.error("[RAG-FACADE] ES 召回失败", e);
                return new ArrayList<>(); // 失败返回空列表
            }
        });

        // 向量召回（语义模糊匹配）
        CompletableFuture<List<RagCandidate>> vectorFuture = CompletableFuture.supplyAsync(() -> {
            try {
                List<RagCandidate> candidates = vectorRecallService.recall(query, contentType, vectorTopK);
                log.info("[RAG-FACADE] 向量召回完成: {} 条", candidates != null ? candidates.size() : 0);
                return candidates != null ? candidates : new ArrayList<>();
            } catch (Exception e) {
                log.error("[RAG-FACADE] 向量召回失败", e);
                return new ArrayList<>(); // 失败返回空列表
            }
        });

        // 第 3 步：等待两路召回完成
        CompletableFuture.allOf(esFuture, vectorFuture).join();

        // 第 4 步：获取召回结果
        List<RagCandidate> esCandidates = esFuture.join();
        List<RagCandidate> vectorCandidates = vectorFuture.join();

        // 第 5 步：检查两路是否都失败
        if (esCandidates.isEmpty() && vectorCandidates.isEmpty()) {
            log.info("[RAG-FACADE] 两路召回均无命中，query={}", query);
                return new ArrayList<>();
        }

        // 第 6 步：结果融合
        List<RagCandidate> fusedCandidates = ragFusionService.fuseCandidates(
                esCandidates, vectorCandidates, finalTopK);

        log.info("[RAG-FACADE] 检索融合完成: 最终返回 {} 条", fusedCandidates.size());
        return fusedCandidates;
    }
}
