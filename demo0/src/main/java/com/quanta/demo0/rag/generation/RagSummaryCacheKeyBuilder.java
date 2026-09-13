package com.quanta.demo0.rag.generation;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 总结缓存键构建器
 * ============================
 * 作用说明
 * ============================
 * 根据 query、contentType、候选列表生成稳定的缓存指纹
 * 指纹规则（按 Plan 1.2 节）：
 *   1. 规范化 query（trim + 可选 toLowerCase）
 *   2. contentType null 时用 "all"
 *   3. 候选指纹：docKind|contentId|answerId（answerId null 用 0）
 *   4. 候选排序后拼接，SHA-256 取前 32 字符
 */
@Component
@Slf4j
public class RagSummaryCacheKeyBuilder {

    /**
     * 构建缓存 key
     * ============================
     * @param query       用户搜索词
     * @param contentType 内容类型（null 表示全部）
     * @param candidates  融合后的候选列表
     * @param properties  RAG 配置属性
     * @return Redis 缓存 key
     */
    public String buildCacheKey(String query, Integer contentType, List<RagCandidate> candidates, RagProperties properties) {
        // 1. 规范化 query
        String normalizedQuery = query.trim();
        if (properties.isSummaryCacheNormalizeCase()) {
            normalizedQuery = normalizedQuery.toLowerCase();
        }

        // 2. contentType 哨兵
        String contentTypeStr = contentType == null ? "all" : contentType.toString();

        // 3. 候选指纹拼接
        String candidatesFingerprint = buildCandidatesFingerprint(candidates);

        // 4. 完整字符串
        String rawKey = normalizedQuery + "|" + contentTypeStr + "|" + candidatesFingerprint;

        // 5. SHA-256 哈希
        String hash = sha256(rawKey);

        // 6. 返回完整 key
        return RedisConstants.RAG_AI_SUMMARY_KEY + hash;
    }

    /**
     * 构建候选列表指纹
     * ============================
     * 每条候选生成稳定串：docKind|contentId|answerId
     * answerId 为 null 时用 0
     * 所有候选串排序后 join
     */
    private String buildCandidatesFingerprint(List<RagCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "empty";
        }

        List<String> fingerprints = candidates.stream()
                .map(candidate -> {
                    String docKind = candidate.getDocKind() != null ? candidate.getDocKind() : "POST";
                    Long contentId = candidate.getContentId();
                    Long answerId = candidate.getAnswerId();

                    // answerId null 用 0，禁止用 "null" 字符串
                    String answerIdStr = answerId == null ? "0" : answerId.toString();

                    return docKind + "|" + contentId + "|" + answerIdStr;
                })
                .sorted()
                .collect(Collectors.toList());

        return String.join("\n", fingerprints);
    }

    /**
     * SHA-256 哈希，返回十六进制前 32 字符
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // 转十六进制
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            // 取前 32 字符
            return hexString.length() > 32 ? hexString.substring(0, 32) : hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("[RAG-CACHE] SHA-256 算法不可用", e);
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }
}