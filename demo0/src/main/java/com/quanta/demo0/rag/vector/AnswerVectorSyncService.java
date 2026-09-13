package com.quanta.demo0.rag.vector;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagContextDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答向量同步服务（MySQL → 向量库）
 * ============================
 * 作用说明
 * ============================
 * 这个类负责把 MySQL 里的回答数据同步到向量库（SimpleVectorStore）。
 * 保证向量库和数据库的数据一致。
 * ============================
 * 同步场景（什么时候调用这个类的方法）
 * ============================
 * 1. 用户发布新回答 → upsertByAnswerId(answerId)
 *    把新回答转为向量，写入向量库
 * 2. 用户编辑回答 → upsertByAnswerId(answerId)
 *    先删旧向量，再写入新向量（因为回答内容变了，向量也要变）
 * 3. 用户删除回答 → deleteByAnswerId(answerId)
 *    从向量库中删除该回答的向量
 * 4. 管理员审核通过 → upsertByAnswerId(answerId)
 *    回答从"待审核"变为"已通过"，可以进入向量库
 * 5. 管理员审核驳回 → deleteByAnswerId(answerId)
 *    回答从"已通过"变为"已驳回"，从向量库移除
 * 6. 索引修复/首次部署 → rebuildAll(batchSize)
 *    把 MySQL 里所有有效回答批量导入向量库
 * ============================
 * 异常处理策略（重要）
 * ============================
 * 所有方法内部吞并异常并记录日志，不抛到上层
 * 原因：向量同步是"锦上添花"的异步操作
 *       发帖成功是主流程，向量同步失败不应该让发帖失败
 *       最坏情况：向量库暂时不一致，下次发帖/重建时会修复
 * 日志级别：
 *   info  - 正常操作记录（开始同步、同步成功）
 *   warn  - 可恢复的问题（回答不存在、状态无效）
 *   error - 不可恢复的问题（向量化失败、保存失败）
 */
@Service
@Slf4j
public class AnswerVectorSyncService {

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private ContentMapper contentMapper;

    @Autowired
    private VectorStorePersistenceService persistenceService;

    @Autowired
    private RagDocumentConverter documentConverter;

    @Autowired
    private SimpleVectorStore vectorStore;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据回答 ID 同步向量（新增/编辑/审核通过）
     * ============================
     * 执行流程（一步步拆解）
     * ============================
     * 第 1 步：从 MySQL 查询回答
     *   调用 questionMapper.selectById(answerId)
     *   如果查不到 → 记录 warn 日志，直接返回
     * 第 2 步：校验回答状态
     *   检查两个条件：
     *     a. isDeleted == 0（未软删除）
     *     b. auditStatus == 1（审核已通过）
     *   任一条件不满足 → 说明回答不应该出现在向量库中
     *     → 调用 deleteVectorOnly() 删除旧向量
     *     → 调用 saveSafely() 落盘
     *     → 返回
     * 第 3 步：有效回答 → 先删旧向量（幂等）
     * 为什么要先删？
     *     因为回答可能被编辑过，旧向量是基于旧内容生成的
     *     不删直接 add 会导致向量库中有两个相同 answerId 的向量
     *   为什么叫"幂等"？
     *     即使旧向量不存在（首次发布），delete 也不会报错
     *     多次调用结果一样
     * 第 4 步：QuestionAnswer → Document → 写入向量库
     *   调用 documentConverter.answerToDocument(answer)
     *     内部流程：QuestionAnswer → RagContextDocument → Document
     *     向量化文本：问题标题 + 问题正文 + 回答正文
     *   调用 vectorStore.add(List.of(document))
     *     把 Document 写入向量库，Embedding 模型自动向量化
     * 第 5 步：落盘
     *   调用 persistenceService.saveSafely()
     *   把内存中的向量写入 JSON 文件，重启不丢失
     * ============================
     * @param answerId 回答 ID
     */
    public void upsertByAnswerId(Long answerId) {
        try {
            // 第 1 步：从 MySQL 查询回答
            QuestionAnswer answer = questionMapper.selectById(answerId);
            if (answer == null) {
                log.warn("[RAG] 回答不存在，跳过向量同步: answerId={}", answerId);
                return;
            }

            // 第 2 步：校验回答状态
            if (answer.getIsDeleted() == 1 || answer.getAuditStatus() != 1) {
                log.warn("[RAG] 回答状态无效，删除向量（如果存在）: answerId={}, isDeleted={}, auditStatus={}",
                        answerId, answer.getIsDeleted(), answer.getAuditStatus());
                deleteVectorOnly(answerId);
                persistenceService.saveSafely();
                return;
            }

            // 第 3 步：有效回答 → 先删旧向量（幂等）
            deleteVectorOnly(answerId);

            // 第 4 步：QuestionAnswer → RagContextDocument → chunk 切分 → 写入向量库
            RagContextDocument ragDoc = documentConverter.toRagDocument(answer);
            List<Document> documents = documentConverter.toDocumentsForIndexing(ragDoc);
            vectorStore.add(documents);

            // 记录 chunk 数量到 Redis
            int chunkTotal = documents.size();
            String redisKey = RedisConstants.RAG_VEC_CHUNKS_ANSWER_KEY + answerId;
            try {
                stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(chunkTotal));
                log.info("[ANSWER-VECTOR] 已记录 chunk 数量: answerId={}, chunks={}", answerId, chunkTotal);
            } catch (Exception e) {
                log.warn("[ANSWER-VECTOR] Redis 写入 chunkCount 失败，不影响主流程: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("[RAG] 同步回答向量失败: answerId={}, error={}", answerId, e.getMessage(), e);
        }
    }

    /**
     * 根据回答 ID 删除向量（删除回答/审核驳回）
     * ============================
     * 执行流程
     * ============================
     * 第 1 步：调用 deleteVectorOnly() 从向量库删除
     * 第 2 步：调用 saveSafely() 落盘
     * ============================
     * @param answerId 回答 ID
     */
    public void deleteByAnswerId(Long answerId) {
        try {
            // 第 1 步：从向量库删除
            deleteVectorOnly(answerId);

            // 第 2 步：落盘
            persistenceService.saveSafely();

            log.info("[RAG] 成功删除回答向量: answerId={}", answerId);
        } catch (Exception e) {
            log.error("[RAG] 删除回答向量失败: answerId={}, error={}", answerId, e.getMessage(), e);
        }
    }

    /**
     * 全量重建回答向量库（管理端接口调用）
     * ============================
     * 使用场景
     * ============================
     * 1. 首次部署后导入历史数据
     *    向量库是空的，需要把 MySQL 里已有的回答全部导入
     * 2. 向量文件损坏后修复
     *    JSON 文件损坏或丢失，重新从 MySQL 导入
     * 3. Embedding 模型升级后重新向量化
     *    换了新的 Embedding 模型，向量维度或语义空间变了
     *    需要把所有回答重新向量化
     * ============================
     * 执行流程
     * ============================
     * 第 1 步：分批查询所有有效回答
     *   调用 questionMapper.selectAllForReindex(offset, batchSize)
     *   每次查 batchSize 条，避免一次性加载太多数据到内存
     * 第 2 步：逐批转换并写入向量库
     *   每批回答 → 批量转为 Document → 批量写入向量库
     * 第 3 步：每批完成后 saveSafely() 落盘
     *   避免全部处理完才保存，万一中间出错会丢失所有进度
     * ============================
     * @param batchSize 每批处理数量，建议 50-100
     *   太小 → 频繁 IO，效率低
     *   太大 → 内存占用高，Embedding API 可能超时
     */
    public void rebuildAll(int batchSize) {
        try {
            log.info("[RAG] 开始全量重建回答向量库: batchSize={}", batchSize);

            int offset = 0;
            int totalProcessed = 0;

            while (true) {
                // 第 1 步：分批查询
                List<QuestionAnswer> batch = questionMapper.selectAllAnswersForReindex(offset, batchSize);

                if (batch == null || batch.isEmpty()) {
                    log.info("[RAG] 无更多回答，重建结束: 共处理 {} 篇", totalProcessed);
                    break;
                }

                // 第 2 步：过滤出有效回答（未删除 + 审核通过）
                List<QuestionAnswer> validAnswers = batch.stream()
                        .filter(a -> a.getIsDeleted() == 0&& a.getAuditStatus() == 1)
                        .toList();

                    if (!validAnswers.isEmpty()) {
                        // 批量转为 Document（chunk 模式）
                        List<Document> allDocuments = new ArrayList<>();
                        for (QuestionAnswer answer : validAnswers) {
                            RagContextDocument ragDoc = documentConverter.toRagDocument(answer);
                            List<Document> chunkDocs = documentConverter.toDocumentsForIndexing(ragDoc);
                            allDocuments.addAll(chunkDocs);

                            // 记录每个回答的 chunk 数量到 Redis
                            String redisKey = RedisConstants.RAG_VEC_CHUNKS_ANSWER_KEY + answer.getAnswerId();
                            try {
                                stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(chunkDocs.size()));
                            } catch (Exception e) {
                                log.warn("[ANSWER-VECTOR] Redis 写入 chunkCount 失败: answerId={}", answer.getAnswerId());
                            }
                        }

                        // 批量写入向量库
                        vectorStore.add(allDocuments);

                    totalProcessed += allDocuments.size();
                    log.info("[RAG] 重建进度: 本批 {} 个chunk，累计 {} 篇", allDocuments.size(), totalProcessed);
                }

                // 第 3 步：每批完成后落盘
                persistenceService.saveSafely();

                // 准备下一批
                offset += batchSize;
            }

            log.info("[RAG] 全量重建回答向量完成: 共 {} 篇", totalProcessed);

        } catch (Exception e) {
            log.error("[RAG] 全量重建回答向量失败", e);
        }
    }

    /**
     * 仅删除向量（不保存），内部私有方法
     * ============================
     * 为什么单独抽出来？
     * ============================
     * upsertByAnswerId() 和 deleteByAnswerId() 都需要删除向量
     * 抽成私有方法避免代码重复
     * ============================
     * 删除原理
     * ============================
     * SimpleVectorStore 的 delete() 方法接受 Document id 数组
     * 我们用 "a:" + answerId 作为 Document id（与 RagDocumentConverter 保持一致）
     * 所以直接传带前缀的字符串就能定位并删除
     * ============================
     * @param answerId 回答 ID
     */
    private void deleteVectorOnly(Long answerId) {
        List<String> documentIds = new ArrayList<>();
        String redisKey = RedisConstants.RAG_VEC_CHUNKS_ANSWER_KEY + answerId;

        try {
            // 1. 尝试从 Redis 获取 chunk 数量
            String chunkCountStr = stringRedisTemplate.opsForValue().get(redisKey);

            if (chunkCountStr != null) {
                // 有记录：精确删除
                int chunkTotal = Integer.parseInt(chunkCountStr);
                for (int i = 0; i < chunkTotal; i++) {
                    documentIds.add("a:" + answerId + ":chunk:" + i);
                }
                log.info("[ANSWER-VECTOR] 按 chunkCount 精确删除: answerId={}, chunks={}", answerId, chunkTotal);
            } else {
                // 无记录（老数据）：legacy 兜底
                documentIds.add("a:" + answerId);
                int maxChunks = ragProperties.getMaxChunksPerDoc();
                for (int i = 0; i < maxChunks; i++) {
                    documentIds.add("a:" + answerId + ":chunk:" + i);
                }
                log.warn("[ANSWER-VECTOR] 无 chunkCount 记录，使用 legacy 兜底: answerId={}, maxChunks={}", answerId, maxChunks);
            }
        } catch (Exception e) {
            log.warn("[ANSWER-VECTOR] Redis 读取 chunkCount 失败，使用 legacy 兜底: {}", e.getMessage());
            // 降级：legacy 单 id + 猜测 chunk
            documentIds.add("a:" + answerId);
            int maxChunks = ragProperties.getMaxChunksPerDoc();
            for (int i = 0; i < maxChunks; i++) {
                documentIds.add("a:" + answerId + ":chunk:" + i);
            }
        }

        vectorStore.delete(documentIds);

        // 清理 Redis 记录
        try {
            stringRedisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("[ANSWER-VECTOR] Redis 删除 chunkCount 记录失败: {}", e.getMessage());
        }
    }
}