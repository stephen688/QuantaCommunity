package com.quanta.demo0.rag.vector;

import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagContextDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


/**
 * 帖子向量同步服务(mysql → 向量库)
 * ============================
 * 作用说明
 * ============================
 * 这个类负责把 MySQL 里的帖子数据同步到向量库（SimpleVectorStore）。
 * 保证向量库和数据库的数据一致。
 * ============================
 * 同步场景（什么时候调用这个类的方法）
 * ============================
 * 1. 用户发布新帖子 → upsertByContentId(contentId)
 *    把新帖子转为向量，写入向量库
 * 2. 用户编辑帖子 → upsertByContentId(contentId)
 *    先删旧向量，再写入新向量（因为标题/正文变了，向量也要变）
 * 3. 用户删除帖子 → deleteByContentId(contentId)
 *    从向量库中删除该帖子的向量
 * 4. 管理员审核通过 → upsertByContentId(contentId)
 *    帖子从"待审核"变为"已通过"，可以进入向量库
 * 5. 管理员审核驳回 → deleteByContentId(contentId)
 *    帖子从"已通过"变为"已驳回"，从向量库移除
 * 6. 索引修复/首次部署 → rebuildAll(batchSize)
 *    把 MySQL 里所有有效帖子批量导入向量库
 * ============================
 * 异常处理策略（重要）
 * ============================
 * 所有方法内部吞并异常并记录日志，不抛到上层
 * 为什么？
 *   向量同步是"锦上添花"的异步操作
 *   发帖成功是主流程，向量同步失败不应该让发帖失败
 *   最坏情况：向量库暂时不一致，下次发帖/重建时会修复
 * 日志级别：
 *   info  - 正常操作记录（开始同步、同步成功）
 *   warn  - 可恢复的问题（帖子不存在、状态无效）
 *   error - 不可恢复的问题（向量化失败、保存失败）
 */

@Service
@Slf4j
public class ContentVectorSyncService {


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
     * 根据帖子 ID 同步向量（新增/编辑/审核通过）
     * ============================
     * 执行流程（一步步拆解）
     * ============================
     * 第 1 步：从 MySQL 查询帖子
     *   调用 contentMapper.selectById(contentId)
     *   如果查不到 → 记录 warn 日志，直接返回
     * 第 2 步：校验帖子状态
     *   检查两个条件：
     *     a. isDeleted == 0（未软删除）
     *     b. auditStatus == 1（审核已通过）
     *   任一条件不满足 → 说明帖子不应该出现在向量库中
     *     → 调用 deleteVectorOnly() 删除旧向量
     *     → 调用 saveSafely() 落盘
     *     → 返回
     * 第 3 步：有效帖子 → 先删旧向量（幂等）
     * 为什么要先删？
     *     因为帖子可能被编辑过，旧向量是基于旧标题/正文生成的
     *     不删直接 add 会导致向量库中有两个相同 contentId 的向量
     *   为什么叫"幂等"？
     *     即使旧向量不存在（首次发布），delete 也不会报错
     *     多次调用结果一样
     * 第 4 步：Content → Document → 写入向量库
     *   调用 documentConverter.contentToDocument(content)
     *     内部流程：Content → RagContextDocument → Document
     *   调用 vectorStore.add(List.of(document))
     *     把 Document 写入向量库，Embedding 模型自动向量化
     * 第 5 步：落盘
     *   调用 persistenceService.saveSafely()
     *   把内存中的向量写入 JSON 文件，重启不丢失
     * ============================
     * @param contentId 帖子 ID
     */
    public void upsertByContentId(Long contentId) {
        try {
            // 第 1 步：从 MySQL 查询帖子
            Content content = contentMapper.selectById(contentId);
            if (content == null) {
                log.warn("[RAG] 帖子不存在，跳过向量同步: contentId={}", contentId);
                return;
            }

            // 第 2 步：校验帖子状态
            if (content.getIsDeleted() == 1 || content.getAuditStatus() != 1) {
                log.warn("[RAG] 帖子状态无效，删除向量（如果存在）: contentId={}, isDeleted={}, auditStatus={}",
                        contentId, content.getIsDeleted(), content.getAuditStatus());
                deleteVectorOnly(contentId);
                persistenceService.saveSafely();
                return;
            }

            // 第 3 步：有效帖子 → 先删旧向量（幂等）
            deleteVectorOnly(contentId);

            // 第 4 步：Content → RagContextDocument → chunk 切分 → 写入向量库
            RagContextDocument ragDoc = documentConverter.toRagDocument(content);
            List<Document> documents = documentConverter.toDocumentsForIndexing(ragDoc);
            vectorStore.add(documents);

            // 记录 chunk 数量到 Redis
            int chunkTotal = documents.size();
            String redisKey = RedisConstants.RAG_VEC_CHUNKS_POST_KEY + contentId;
            try {
                stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(chunkTotal));
                log.info("[CONTENT-VECTOR] 已记录 chunk 数量: contentId={}, chunks={}", contentId, chunkTotal);
            } catch (Exception e) {
                log.warn("[CONTENT-VECTOR] Redis 写入 chunkCount 失败，不影响主流程: {}", e.getMessage());
            }
            // 第 5 步：落盘
            persistenceService.saveSafely();

            log.info("[RAG] 成功同步帖子向量: contentId={}", contentId);
        }catch (Exception e) {
            log.error("[RAG] 同步帖子向量失败: contentId={}, error={}", contentId, e.getMessage(), e);
        }
    }

    /**
     * 根据帖子 ID 删除向量（删除帖子/审核驳回）
     * ============================
     * 执行流程
     * ============================
     * 第 1 步：调用 deleteVectorOnly() 从向量库删除
     * 第 2 步：调用 saveSafely() 落盘
     * ============================
     * @param contentId 帖子 ID
     */

    public void deleteByContentId(Long contentId) {
        try {
            // 第 1 步：从向量库删除
            deleteVectorOnly(contentId);

            // 第 2 步：落盘
            persistenceService.saveSafely();

            log.info("[RAG] 成功删除帖子向量: contentId={}", contentId);
        } catch (Exception e) {
            log.error("[RAG] 删除帖子向量失败: contentId={}, error={}", contentId, e.getMessage(), e);
        }
    }

/**
 * 全量重建向量库（管理端接口调用）
 * ============================
 * 使用场景
 * ============================
 * 1. 首次部署后导入历史数据
 *    向量库是空的，需要把 MySQL 里已有的帖子全部导入
 * 2. 向量文件损坏后修复
 *    JSON 文件损坏或丢失，重新从 MySQL 导入
 * 3. Embedding 模型升级后重新向量化
 *    换了新的 Embedding 模型，向量维度或语义空间变了
 *    需要把所有帖子重新向量化
 * ============================
 * 执行流程
 * ============================
 * 第 1 步：分批查询所有有效帖子
 *   调用 contentMapper.selectAllForReindex(offset, batchSize)
 *   每次查 batchSize 条，避免一次性加载太多数据到内存
 * 第 2 步：逐批转换并写入向量库
 *   每批帖子 → 批量转为 Document → 批量写入向量库
 * 第 3 步：每批完成后 saveSafely() 落盘
 *   避免全部处理完才保存，万一中间出错会丢失所有进度
 * ============================
 * @param batchSize 每批处理数量，建议 50-100
 *   太小 → 频繁 IO，效率低
 *   太大 → 内存占用高，Embedding API 可能超时
 */
public void rebuildAll(int batchSize) {
    try {
        log.info("[RAG] 开始全量重建向量库: batchSize={}", batchSize);

        // 第 0 步：清空现有向量库（如果有）
        String filePath = ragProperties.getVectorFilePath();
        File vectorFile = new File(filePath);
        if (vectorFile.exists()) {
            boolean deleted = vectorFile.delete();
            if (deleted) {
                log.info("[RAG] 旧向量文件已删除: {}", filePath);
            } else {
                log.warn("[RAG] 旧向量文件删除失败: {}", filePath);
            }
        }
        int offset = 0;// 分批查询的偏移量
        int totalProcessed = 0;

        while (true) {
            // 第 1 步：分批查询
            List<Content> batch = contentMapper.selectAllForReindex(offset, batchSize);

            if (batch == null || batch.isEmpty()) {
                log.info("[RAG] 无更多帖子，重建结束: 共处理 {} 篇", totalProcessed);
                break;
            }

            // 第 2 步：过滤出有效帖子（未删除 + 审核通过）
            List<Content> validContents = batch.stream()
                    .filter(c -> c.getIsDeleted() == 0
                            && c.getAuditStatus() == AuditStatus.APPROVED.getCode())
                    .toList();

            if (!validContents.isEmpty()) {
                // 批量转为 Document（chunk 模式）
                List<Document> allDocuments = new ArrayList<>();
                for (Content content : validContents) {
                    RagContextDocument ragDoc = documentConverter.toRagDocument(content);
                    List<Document> chunkDocs = documentConverter.toDocumentsForIndexing(ragDoc);
                    allDocuments.addAll(chunkDocs);

                    // 记录每个帖子的 chunk 数量到 Redis
                    String redisKey = RedisConstants.RAG_VEC_CHUNKS_POST_KEY + content.getContentId();
                    try {
                        stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(chunkDocs.size()));
                    } catch (Exception e) {
                        log.warn("[CONTENT-VECTOR] Redis 写入 chunkCount 失败: contentId={}", content.getContentId());
                    }
                }

                // 批量写入向量库
                vectorStore.add(allDocuments);

                totalProcessed +=allDocuments .size();
                log.info("[RAG] 重建进度: 本批 {} 篇，累计 {} 篇", allDocuments.size(), totalProcessed);
            }

            // 第 3 步：每批完成后落盘
            persistenceService.saveSafely();

            // 准备下一批
            offset += batchSize;
        }

        log.info("[RAG] 全量重建完成: 共 {} 篇帖子", totalProcessed);

    } catch (Exception e) {
        log.error("[RAG] 全量重建失败", e);
    }
}

    /**
     *  仅删除向量（不保存），内部私有方法
     *  ============================
     *  为什么单独抽出来？
     *  ============================
     *  upsertByContentId() 和 deleteByContentId() 都需要删除向量
     *  抽成私有方法避免代码重复
     *  ============================
     * @param contentId
     */
    private void deleteVectorOnly(Long contentId) {
        List<String> documentIds = new ArrayList<>();
        String redisKey = RedisConstants.RAG_VEC_CHUNKS_POST_KEY + contentId;

        try {
            // 1. 尝试从 Redis 获取 chunk 数量
            String chunkCountStr = stringRedisTemplate.opsForValue().get(redisKey);

            if (chunkCountStr != null) {
                // 有记录：精确删除
                int chunkTotal = Integer.parseInt(chunkCountStr);
                for (int i = 0; i < chunkTotal; i++) {
                    documentIds.add("c:" + contentId + ":chunk:" + i);
                }
                log.info("[CONTENT-VECTOR] 按 chunkCount 精确删除: contentId={}, chunks={}", contentId, chunkTotal);
            } else {
                // 无记录（老数据）：legacy 兜底
                documentIds.add("c:" + contentId);
                int maxChunks = ragProperties.getMaxChunksPerDoc();
                for (int i = 0; i < maxChunks; i++) {
                    documentIds.add("c:" + contentId + ":chunk:" + i);
                }
                log.warn("[CONTENT-VECTOR] 无 chunkCount 记录，使用 legacy 兜底: contentId={}, maxChunks={}", contentId, maxChunks);
            }
        } catch (Exception e) {
            log.warn("[CONTENT-VECTOR] Redis 读取 chunkCount 失败，使用 legacy 兜底: {}", e.getMessage());
            // 降级：legacy 单 id + 猜测 chunk
            documentIds.add("c:" + contentId);
            int maxChunks = ragProperties.getMaxChunksPerDoc();
            for (int i = 0; i < maxChunks; i++) {
                documentIds.add("c:" + contentId + ":chunk:" + i);
            }
        }

        vectorStore.delete(documentIds);

        // 清理 Redis 记录
        try {
            stringRedisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("[CONTENT-VECTOR] Redis 删除 chunkCount 记录失败: {}", e.getMessage());
        }
    }
}



