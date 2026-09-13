package com.quanta.demo0.rag.vector;

import com.quanta.demo0.properties.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 向量存储持久化服务(向量库->本地 JSON 文件)
 * ============================
 * 作用说明
 * ============================
 * 这个类负责把内存里的向量数据保存到本地 JSON 文件，以及从文件加载回内存。
 * 为什么需要持久化？
 *   SimpleVectorStore 是纯内存存储，应用重启后所有向量数据会丢失
 *   通过 save/load JSON 文件，实现"重启后向量不丢失"
 * 工作流程：
 *   1. 应用启动 → loadIfExists() → 从 JSON 文件加载向量到内存
 *   2. 帖子发布/编辑/删除 → upsert/delete → 内存中向量变更
 *   3. 每次变更后 → saveSafely() → 把最新向量写回 JSON 文件
 * ============================
 * 为什么 saveSafely() 要加 synchronized？
 * ============================
 * 场景：两个用户同时发帖，两个线程同时调用 upsertByContentId()
 *   线程A：写入向量 → 准备 save
 *   线程B：写入向量 → 准备 save
 *   如果不加锁：两个线程同时写文件，JSON 数据会损坏
 *   加了 synchronized：同一时刻只有一个线程能写文件，保证数据安全
 * ============================
 * 异常处理策略
 * ============================
 * saveSafely() 内部吞并异常，不抛到上层
 * 原因：
 *   - 向量同步是异步操作，失败不应影响主业务流程（发帖/删帖）
 *   - 最坏情况：重启后丢失最近一次向量变更，下次发帖时会重新同步
 *   - 记录 error 日志，方便排查问题
 */
@Service
@Slf4j
public class VectorStorePersistenceService {


    @Autowired
    private SimpleVectorStore vectorStore;


    @Autowired
    private RagProperties ragProperties;


    /**
     * 本地到缓存的加载方法

     * ============================
     * 调用时机
     * ============================
     * 应用启动时由 VectorStoreInitializer 调用
     * 或者 VectorStoreConfig 创建 SimpleVectorStore 时直接调用

     * ============================
     * 执行流程
     * ============================
     * 1. 检查文件是否存在
     * 2. 存在 → 调用 vectorStore.load() 加载到内存
     * 3. 不存在 → 返回 false，使用空向量库
     * 4. 加载失败 → 记录错误日志，返回 false
     *
     * @return true-加载成功，false-文件不存在或加载失败
     */
    public boolean loadIfExists() {
        String filePath = ragProperties.getVectorFilePath();
        File vectorFile = new File(filePath);

        if (vectorFile.exists()) {
            try {
                vectorStore.load(vectorFile);
                log.info("[RAG] 向量文件加载成功: {}", filePath);
                return true;
            } catch (Exception e) {
                log.warn("[RAG] 向量文件加载失败，将使用空向量库: {}", filePath, e);
                return false;
            }
        } else {
            log.info("[RAG] 向量文件不存在，使用空向量库: {}", filePath);
            return false;
        }
    }
        /**
         * 安全保存向量到本地文件
         * ============================
         * 为什么加 synchronized？
         * ============================
         * 防止多个线程同时写文件导致数据损坏
         * 例如：线程A和线程B同时发帖，都调用 saveSafely()
         * 不加锁 → 两个线程同时写 → JSON 文件内容混乱
         * 加锁 → 线程A写完，线程B再写 → 数据安全*
         * ============================
         * 调用时机
         * ============================
         * 以下操作完成后必须调用：
         *   - ContentVectorSyncService.upsertByContentId() → 帖子向量新增/更新后
         *   - ContentVectorSyncService.deleteByContentId() → 帖子向量删除后
         *   - ContentVectorSyncService.rebuildAll() → 每批次重建完成后
         * ============================
         * 执行流程
         * ============================
         * 1. 获取文件路径
         * 2. 确保父目录存在（不存在则创建）
         * 3. 调用 vectorStore.save() 写入文件
         * 4. 成功 → 记录 debug 日志
         * 5. 失败 → 记录 error 日志，不抛异常
         */
    public synchronized void saveSafely() {
        String filePath = ragProperties.getVectorFilePath();

        try {
            File vectorFile = new File(filePath);
            File parentDir = vectorFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (created) {
                    log.info("[RAG] 向量存储目录已创建: {}", parentDir.getAbsolutePath());
                }
            }

            vectorStore.save(vectorFile);
            log.info("[RAG] 向量文件保存成功: {}", filePath);
        } catch (Exception e) {
            log.error("[RAG] 向量文件保存失败: {}", filePath, e);

        }
    }
}
