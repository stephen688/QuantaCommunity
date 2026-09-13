package com.quanta.demo0.rag.vector;

import com.quanta.demo0.properties.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 向量存储启动初始化器
 * ============================
 * 作用说明
 * ============================
 * 这个类在 Spring Boot 应用启动完成后自动执行，负责初始化向量库。
 * 实现 ApplicationRunner 接口：
 *   Spring Boot 启动时会自动调用 run() 方法
 *   调用时机：所有 Bean 初始化完成后，应用开始接收请求前
 * ============================
 * 执行流程（启动时发生了什么）
 * ============================
 * 第 1 步：尝试从本地 JSON 文件加载向量
 *   调用 persistenceService.loadIfExists()
 *   文件存在且加载成功 → 跳过重建，直接使用
 *   文件不存在或加载失败 → 进入第 2 步
 * 第 2 步：判断是否开启 bootstrap（全量导入）
 *   检查 ragProperties.isBootstrapEnabled()
 *   true  → 调用 rebuildAll(50) 从 MySQL 全量导入
 *   false → 使用空向量库，等待后续发帖时逐步同步
 * ============================
 * bootstrapEnabled 配置说明
 * ============================
 * 在 application.yml 中配置：
 *   rag:
 *     vector:
 *       bootstrap-enabled: false  # 默认 false
 * 什么时候设为 true？
 *   - 首次部署，MySQL 里已有历史帖子数据
 *   - 向量文件丢失，需要重新导入
 *   - 更换了 Embedding 模型，需要重新向量化
 * 什么时候保持 false？
 *   - 日常启动，向量文件正常存在
 *   - 新系统刚上线，MySQL 里还没数据
 * ============================
 * 日志输出示例
 * ============================
 * 场景 1：向量文件存在
 *   [RAG] ========== 向量存储初始化开始 ==========
 *   [RAG] 向量文件加载成功: ./data/vector-store/content-vector-store.json
 *   [RAG] 向量文件加载成功，跳过重建
 *   [RAG] ========== 向量存储初始化完成 ==========
 * 场景 2：文件不存在，bootstrapEnabled=true
 *   [RAG] ========== 向量存储初始化开始 ==========
 *   [RAG] 向量文件不存在，跳过加载: ./data/vector-store/content-vector-store.json
 *   [RAG] 向量文件不存在，开始全量导入...
 *   [RAG] 开始全量重建向量库: batchSize=50
 *   [RAG] 重建进度: 本批 50 篇，累计 50 篇
 *   [RAG] 全量重建完成: 共 128 篇帖子
 *   [RAG] ========== 向量存储初始化完成 ===========
 * 场景 3：文件不存在，bootstrapEnabled=false
 *   [RAG] ========== 向量存储初始化开始 ==========
 *   [RAG] 向量文件不存在，跳过加载: ./data/vector-store/content-vector-store.json
 *   [RAG] 向量文件不存在，bootstrapEnabled=false，使用空向量库
 *   [RAG] ========== 向量存储初始化完成 ==========
 */
@Component
@Slf4j
public class VectorStoreInitializer implements ApplicationRunner {

@Autowired
private VectorStorePersistenceService persistenceService;
@Autowired
private RagProperties ragProperties;
@Autowired
private ContentVectorSyncService contentVectorSyncService;
@Autowired
private AnswerVectorSyncService answerVectorSyncService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("[RAG] ========== 向量存储初始化开始 ==========");

        // 第 1 步：尝试从本地 JSON 文件加载向量
        boolean loaded = persistenceService.loadIfExists();
        if (loaded) {
            log.info("[RAG] 向量文件加载成功，跳过重建");
        } else {
            log.warn("[RAG] 向量文件不存在或加载失败，跳过加载: {}", ragProperties.getVectorFilePath());

            // 第 2 步：判断是否开启 bootstrap（全量导入）
            if (ragProperties.isBootstrapEnabled()) {
                log.info("[RAG] 向量文件不存在，bootstrapEnabled=true，开始全量导入...");
               contentVectorSyncService.rebuildAll(50);// 每批处理 50 篇帖子
                answerVectorSyncService.rebuildAll(50);  // 每批处理 50 篇回答
            } else {
                log.warn("[RAG] 向量文件不存在，bootstrapEnabled=false，使用空向量库");
            }
        }

        log.info("[RAG] ========== 向量存储初始化完成 ==========");
    }
}
