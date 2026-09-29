package com.quanta.demo0.rag.vector;

import com.quanta.demo0.rag.model.RagContextDocument;
import com.quanta.demo0.rag.properties.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG chunk 与 Spring AI Document 转换器。
 *
 * <p>集中维护旧向量库的文本构造、chunk ID 和 metadata 契约。内容和回答转换器只
 * 负责生成 {@link RagContextDocument}，避免重复实现这些持久化标识规则。</p>
 */
@Component
@RequiredArgsConstructor
public class RagChunkDocumentConverter {

    private final RagProperties ragProperties;
    private final RagTextChunker textChunker;

    /**
     * 将未切分的上下文文档转换为兼容旧索引的单个 Document。
     *
     * @param ragDocument RAG 上下文文档
     * @return Spring AI 文档；输入为空时返回 {@code null}
     */
    public Document toDocument(RagContextDocument ragDocument) {
        if (ragDocument == null) {
            return null;
        }

        Map<String, Object> metadata = buildMetadata(ragDocument);
        String documentId;
        if (ragDocument.getAnswerId() != null) {
            metadata.put("answerId", ragDocument.getAnswerId());
            metadata.put("docKind", "ANSWER");
            metadata.put("contentType", 2);
            String answerContent = ragDocument.getContent();
            if (answerContent != null && answerContent.contains("\n")) {
                int lastNewlineIndex = answerContent.lastIndexOf("\n");
                if (lastNewlineIndex > 0) {
                    String answerOnly = answerContent.substring(lastNewlineIndex + 1);
                    if (!answerOnly.isEmpty()) {
                        metadata.put("answerSnippet", answerOnly);
                    }
                }
            }
            documentId = "a:" + ragDocument.getAnswerId();
        } else {
            metadata.put("docKind", "POST");
            documentId = "c:" + ragDocument.getContentId();
        }

        return Document.builder()
                .id(documentId)
                .text(buildText(ragDocument))
                .metadata(metadata)
                .build();
    }

    /**
     * 将上下文文档切分为用于索引写入的 Document 列表。
     *
     * @param context RAG 上下文文档
     * @return 按旧 ID/metadata 规则生成的 chunk 文档
     */
    public List<Document> toDocumentsForIndexing(RagContextDocument context) {
        if (context == null) {
            return List.of();
        }
        String textForEmbedding = buildText(context);
        List<String> chunks = textChunker.chunk(textForEmbedding, ragProperties);
        int chunkTotal = chunks.size();
        List<Document> documents = new ArrayList<>(chunkTotal);
        for (int i = 0; i < chunkTotal; i++) {
            String chunkText = chunks.get(i);
            documents.add(new Document(
                    buildChunkId(context, i),
                    chunkText,
                    buildChunkMetadata(context, i, chunkTotal, chunkText)));
        }
        return documents;
    }

    private String buildText(RagContextDocument ragDocument) {
        StringBuilder text = new StringBuilder();
        if (ragDocument.getTitle() != null && !ragDocument.getTitle().isEmpty()) {
            text.append(ragDocument.getTitle());
        }
        if (ragDocument.getContent() != null && !ragDocument.getContent().isEmpty()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(ragDocument.getContent());
        }
        return text.toString();
    }

    private Map<String, Object> buildMetadata(RagContextDocument context) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("contentId", context.getContentId());
        metadata.put("contentType", context.getContentType());
        metadata.put("publishUserId", context.getPublishUserId());
        // 与旧转换器一致：缺失创建时间是非法索引事实，不能静默省略 metadata。
        metadata.put("createTime", context.getCreateTime().toString());
        return metadata;
    }

    private String buildChunkId(RagContextDocument context, int chunkIndex) {
        if (context.getAnswerId() != null) {
            return "a:" + context.getAnswerId() + ":chunk:" + chunkIndex;
        }
        return "c:" + context.getContentId() + ":chunk:" + chunkIndex;
    }

    private Map<String, Object> buildChunkMetadata(RagContextDocument context,
                                                    int chunkIndex,
                                                    int chunkTotal,
                                                    String chunkText) {
        Map<String, Object> metadata = buildMetadata(context);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("chunkTotal", chunkTotal);
        if (context.getAnswerId() != null) {
            metadata.put("answerId", context.getAnswerId());
            metadata.put("docKind", "ANSWER");
            metadata.put("answerSnippet", chunkText.length() > 200
                    ? chunkText.substring(0, 200) : chunkText);
        } else {
            metadata.put("docKind", "POST");
        }
        return metadata;
    }
}
