package com.quanta.demo0.rag.vector;

import com.quanta.demo0.answer.vo.AnswerRagSnapshotVO;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.rag.model.RagContextDocument;
import com.quanta.demo0.rag.properties.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagDocumentConverterTest {

    @Test
    void 缺少创建时间保持旧转换失败语义() {
        RagChunkDocumentConverter converter = new RagChunkDocumentConverter(
                new RagProperties(), new RagTextChunker());
        RagContextDocument context = RagContextDocument.builder()
                .contentId(10L).contentType(1).content("正文").build();

        assertThatThrownBy(() -> converter.toDocument(context))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> converter.toDocumentsForIndexing(context))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 内容和回答转换保留文本_chunkId以及metadata契约() {
        RagProperties properties = new RagProperties();
        properties.setMinCharsForChunking(10_000);
        RagChunkDocumentConverter chunkConverter = new RagChunkDocumentConverter(
                properties,
                new RagTextChunker());
        ContentRagDocumentConverter contentConverter = new ContentRagDocumentConverter();
        AnswerRagDocumentConverter answerConverter = new AnswerRagDocumentConverter();
        LocalDateTime createTime = LocalDateTime.of(2026, 9, 29, 10, 20);

        RagContextDocument content = contentConverter.toRagDocument(ContentSnapshotVO.builder()
                .contentId(10L)
                .contentType(1)
                .title("内容标题")
                .content("内容正文")
                .publishUserId(7L)
                .createTime(createTime)
                .build());
        List<Document> contentChunks = chunkConverter.toDocumentsForIndexing(content);

        assertThat(contentChunks).hasSize(1);
        assertThat(contentChunks.get(0).getId()).isEqualTo("c:10:chunk:0");
        assertThat(contentChunks.get(0).getText()).isEqualTo("内容标题\n内容正文");
        assertThat(contentChunks.get(0).getMetadata())
                .containsEntry("contentId", 10L)
                .containsEntry("contentType", 1)
                .containsEntry("publishUserId", 7L)
                .containsEntry("docKind", "POST")
                .containsEntry("chunkIndex", 0)
                .containsEntry("chunkTotal", 1);

        RagContextDocument answer = answerConverter.toRagDocument(AnswerRagSnapshotVO.builder()
                .answerId(20L)
                .questionId(10L)
                .userId(8L)
                .questionTitle("问题标题")
                .questionContent("问题正文")
                .content("回答正文")
                .createTime(createTime)
                .build());
        Document answerDocument = chunkConverter.toDocumentsForIndexing(answer).get(0);

        assertThat(answerDocument.getId()).isEqualTo("a:20:chunk:0");
        assertThat(answerDocument.getText()).isEqualTo("问题标题\n问题标题\n问题正文\n回答正文");
        assertThat(answerDocument.getMetadata())
                .containsEntry("contentId", 10L)
                .containsEntry("answerId", 20L)
                .containsEntry("contentType", 2)
                .containsEntry("docKind", "ANSWER")
                .containsEntry("answerSnippet", "问题标题\n问题标题\n问题正文\n回答正文");
    }
}
