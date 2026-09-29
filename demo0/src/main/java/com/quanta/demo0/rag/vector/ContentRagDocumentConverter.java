package com.quanta.demo0.rag.vector;

import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.rag.model.RagContextDocument;
import org.springframework.stereotype.Component;

/**
 * RAG 内容文档转换器。
 *
 * <p>只接受内容域暴露的稳定快照，不直接依赖内容实体或 Mapper；文本切分和
 * Spring AI {@code Document} 的 metadata 由 {@link RagChunkDocumentConverter}
 * 负责。</p>
 */
@Component
public class ContentRagDocumentConverter {

    /**
     * 将内容快照转换为 RAG 上下文文档。
     *
     * @param content 内容域稳定快照
     * @return RAG 上下文文档；输入为空时返回 {@code null}
     */
    public RagContextDocument toRagDocument(ContentSnapshotVO content) {
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
                .build();
    }
}
