package com.quanta.demo0.rag.vector;

import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.QuestionAnswer;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.properties.RagProperties;
import com.quanta.demo0.rag.model.RagContextDocument;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 帖子 ↔ Document 转换器
 * ============================
 * 作用说明
 * ============================
 * 这个类负责把数据库里的 Content 实体，一步步转成 Spring AI 向量库能存储的 Document 对象。
 * 换链路分两步：
 *   第一步：Content → RagContextDocument
 *     把数据库实体转成 RAG 专用的中间结构，只保留向量检索和 AI 引用需要的字段
 *     去掉点赞数、评论数、图片列表等无关字段
 *   第二步：RagContextDocument → Document
 *     把中间结构转成 Spring AI 的 Document 对象
 *     拼接标题+正文作为向量化文本
 *     把 contentId、contentType 等存入 metadata 供检索时过滤
 * ============================
 * 为什么需要 RagContextDocument 这个中间层？
 * ============================
 * 1. 隔离业务实体和 AI 输入：Content 是数据库实体，字段多且可能频繁变更
 *    RagContextDocument 是 RAG 专用结构，字段稳定，不受数据库表结构变化影响

 * 2. 灵活控制向量化内容：可以决定哪些字段参与向量化，哪些只存 metadata
 *    比如 tags 字段暂时不用，可以先注释掉，不影响 Content 实体

 * 3. 方便调试：打印 RagContextDocument 就能清楚看到送入向量库的数据长什么样
 */
@Component
@Slf4j
public class RagDocumentConverter {

    @Autowired
    private ContentMapper contentMapper;
    @Autowired
    private RagProperties ragProperties;
    @Autowired
    private RagTextChunker textChunker;
    /// Content → RagContextDocument 转换方法
    public RagContextDocument toRagDocument(Content content) {
        if (content == null) {
            return null;
        }
        return RagContextDocument.builder()
                .contentId(content.getContentId())
                .title(content.getTitle())
                .content(content.getContent())
                .publishUserId(content.getPublishUserId())
                .contentType(content.getContentType())
                .createTime(content.getCreateTime())
                //.tags(content.getTags()) // 暂时不用标签字段
                .build();
    }

    /**
     * QuestionAnswer → RagContextDocument 转换方法
     * ============================
     * 作用说明
     * ============================
     * 把回答实体转成 RAG 专用的中间结构
     * 向量化文本拼接：问题标题 + 问题正文 + 回答正文
     * 这样用户搜问题时能匹配到相关回答
     * ============================
     * @param answer 回答实体
     * @return RagContextDocument 中间文档结构
     */
    public RagContextDocument toRagDocument(QuestionAnswer answer) {
        if (answer == null) {
            return null;
        }
        // 查询父帖（问题）信息，用于拼接向量化文本
        Content question = contentMapper.selectById(answer.getQuestionId());
        String questionTitle = question != null ? question.getTitle() : "";
        String questionContent = question != null ? question.getContent() : "";

        // 拼接：问题标题 + 换行 + 问题正文 + 换行 + 回答正文
        StringBuilder sb = new StringBuilder();
        if (questionTitle != null && !questionTitle.isEmpty()) {
            sb.append(questionTitle).append("\n");
        }
        if (questionContent != null && !questionContent.isEmpty()) {
            sb.append(questionContent).append("\n");
        }
        if (answer.getContent() != null && !answer.getContent().isEmpty()) {
            sb.append(answer.getContent());
        }

        return RagContextDocument.builder()
                .contentId(answer.getQuestionId()) // 回答的 contentId 填所属问题 ID
                .answerId(answer.getAnswerId())    // 回答自己的 ID
                .title(questionTitle)              // 问题标题
                .content(sb.toString())            // 拼接后的全文本
                .publishUserId(answer.getUserId()) // 回答者 ID
                .contentType(2)                    // 专业区固定为 2
                .createTime(answer.getCreateTime())
                .build();
    }

    /// RagContextDocument → Document 转换方法
    /**
     * 第二步转换：RagContextDocument → Document

     * ============================
     * Document 的三个核心组成部分
     * ============================
     * 1. id（String）：文档唯一标识
     *    使用 contentId 的字符串形式，例如 "1001"
     *    作用：upsert 时用相同 id 可以覆盖旧向量，实现幂等更新
     *    删除时也用这个 id 定位要删的向量

     * 2. text（String）：向量化文本
     *    由 buildText() 方法拼接而成
     *    规则：标题 + 换行 + 正文
     *    示例："考研怎么准备\n我是双非本科，去年成功上岸XX大学..."
     *    这个文本会被 Embedding 模型转为 1536 维向量

     * 3. metadata（Map<String, Object>）：元数据
     *    不参与向量化，但检索时可以用来过滤
     *    例如：检索时只查 contentType=2 的专业区帖子

     * ============================
     * metadata 字段说明
     * ============================
     * contentId      → Long   帖子 ID，检索后用来批量查 ContentVO 详情
     * contentType    → Integer 内容类型，1=生活区 2=专业区，用于分区过滤
     * publishUserId  → Long   发布用户 ID，预留字段，后续可能用于"只看某人的帖子"
     * createTime     → String 发布时间，转字符串存储，用于融合排序时同分按时间降序
     *
     * @param ragDoc RagContextDocument 中间文档结构
     * @return Document Spring AI 向量库存储结构
     */
    public Document toDocument(RagContextDocument ragDoc) {
        if (ragDoc == null) {
            return null;
        }
      //拼接标题和正文作为向量化文本
  String text=  buildText(ragDoc);

        //构建 metadata
        Map<String, Object> metadata= new HashMap<>();
        metadata.put("contentId", ragDoc.getContentId());
        metadata.put("contentType", ragDoc.getContentType());
        metadata.put("publishUserId", ragDoc.getPublishUserId());
        metadata.put("createTime", ragDoc.getCreateTime().toString()); // 转字符串存储

        // 根据文档类型设置 metadata
        if (ragDoc.getAnswerId() != null) {
            // 回答文档：存入 answerId，contentId 就是问题ID
            metadata.put("answerId", ragDoc.getAnswerId());
            metadata.put("docKind", "ANSWER");
            metadata.put("contentType", 2);// 专业区回答固定 contentType=2，检索时按专业区过滤
            // 新增：单独存储回答正文摘要（供向量召回使用）
            String answerContent = ragDoc.getContent();
            if (answerContent != null && answerContent.contains("\n")) {
                // content 格式为"标题\n问题正文\n回答正文"，取最后一个\n之后的内容作为回答
                int lastNewlineIndex = answerContent.lastIndexOf("\n");
                if (lastNewlineIndex > 0) {
                    String answerOnly = answerContent.substring(lastNewlineIndex + 1);
                    if (!answerOnly.isEmpty()) {
                        metadata.put("answerSnippet", answerOnly);
                    }
                }
            }
        } else {
            // 帖子文档
            metadata.put("docKind", "POST");
        }


        // 根据文档类型设置 id：回答用 a:，帖子用 c:
        String docId = ragDoc.getAnswerId() != null
                ? "a:" + ragDoc.getAnswerId()
                : "c:" + ragDoc.getContentId();

        return Document.builder()
                .id(docId)
                .text(text)
                .metadata(metadata)
                .build();
    }

    /// 构建向量化文本的方法（标题 + 正文）
    /**
     * 拼接向量化文本

     * ============================
     * 拼接规则
     * ============================
     * 标题 + 换行 + 正文

     * 为什么要拼接？
     *   Embedding 模型需要一段完整文本来生成向量
     *   单独向量化标题或正文会导致语义不完整
     *   拼接后"标题+正文"一起向量化，语义更准确

     * 为什么用换行分隔？
     *   换行符 \n 是自然的文本分隔符
     *   Embedding 模型能理解换行的语义边界
     *   比用空格或特殊符号更自然

      ============================
     * 空值处理
     * ============================
     * - 标题为空：只向量化正文
     * - 正文为空：只向量化标题
     * - 都为空：返回空字符串（这种情况不应该出现，因为发帖时校验过）
     *
     * @param ragDoc RagContextDocument
     * @return 拼接后的纯文本，用于向量化
     */
    private String buildText(RagContextDocument ragDoc) {
        StringBuilder sb= new StringBuilder();
        if (ragDoc.getTitle() != null && !ragDoc.getTitle().isEmpty()) {
            sb.append(ragDoc.getTitle());
        }

        if (ragDoc.getContent() != null && !ragDoc.getContent().isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n"); // 标题和正文之间用换行分隔
            }
            sb.append(ragDoc.getContent());
        }

        return  sb.toString();

    }

    /// 反向转换：Document → RagContextDocument
/**
 * 便捷方法：直接从 Content 转为 Document

 * ============================
 * 使用场景
 * ============================
 * 这是最常用的方法，一步到位完成转换
 * 内部先调 toRagContextDocument() 再调 toDocument()

 * 适用场景：
 *   - ContentVectorSyncService.upsertByContentId() 中调用
 *   - ContentVectorSyncService.rebuildAll() 中批量调用
 */
    public Document ContentToDocument(Content content) {
        RagContextDocument ragDoc= toRagDocument(content);
        return toDocument(ragDoc);
    }
    /**
     * 便捷方法：直接从 QuestionAnswer 转为 Document
     * ============================
     * 使用场景
     * ============================
     * 这是最常用的方法，一步到位完成转换
     * 内部先调 toRagDocument(answer) 再调 toDocument(ragDoc)
     * 适用场景：
     *   - AnswerVectorSyncService.upsertByAnswerId() 中调用
     *   - AnswerVectorSyncService.rebuildAll() 中批量调用
     * ============================
     * @param answer 回答实体
     * @return Document Spring AI 向量库存储结构
     */
    public Document answerToDocument(QuestionAnswer answer) {
        RagContextDocument ragDoc = toRagDocument(answer);
        return toDocument(ragDoc);
    }

    /**
     * 将 RagContextDocument 切分为多个 Document（用于索引写入）
     * ============================
     * @param context 上下文文档
     * @return Document 列表，每个对应一个 chunk
     */
    public List<Document> toDocumentsForIndexing(RagContextDocument context) {
        String textForEmbedding = buildText(context); // 复用现有 buildText 方法

        // 切分文本
        List<String> chunks = textChunker.chunk(textForEmbedding, ragProperties);
        int chunkTotal = chunks.size();

        // 构建 Document 列表
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunkTotal; i++) {
            String chunkText = chunks.get(i);

            // 构建 chunk id
            String docId = buildChunkId(context, i);

            // 构建 metadata
            Map<String, Object> metadata = buildChunkMetadata(context, i, chunkTotal, chunkText);

            documents.add(new Document(docId, chunkText, metadata));
        }

        return documents;
    }

    /**
     * 构建 chunk id
     * POST: c:{contentId}:chunk:{i}
     * ANSWER: a:{answerId}:chunk:{i}
     */
    private String buildChunkId(RagContextDocument context, int chunkIndex) {
        if (context.getAnswerId() != null) {
            return "a:" + context.getAnswerId() + ":chunk:" + chunkIndex;
        } else {
            return "c:" + context.getContentId() + ":chunk:" + chunkIndex;
        }
    }

    /**
     * 构建 chunk metadata
     */
    private Map<String, Object> buildChunkMetadata(RagContextDocument context, int chunkIndex, int chunkTotal, String chunkText) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("contentId", context.getContentId());
        metadata.put("contentType", context.getContentType());
        metadata.put("publishUserId", context.getPublishUserId());
        metadata.put("createTime", context.getCreateTime().toString());
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("chunkTotal", chunkTotal);

        // 根据是否有 answerId 判断文档类型
        if (context.getAnswerId() != null) {
            metadata.put("answerId", context.getAnswerId());
            metadata.put("docKind", "ANSWER");
            // answerSnippet：取 chunkText 的前 200 字符作为摘要
            metadata.put("answerSnippet", chunkText.length() > 200 ? chunkText.substring(0, 200) : chunkText);
        } else {
            metadata.put("docKind", "POST");
        }

        return metadata;
    }

 }
