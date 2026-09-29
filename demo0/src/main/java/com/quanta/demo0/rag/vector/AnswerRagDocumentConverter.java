package com.quanta.demo0.rag.vector;

import com.quanta.demo0.answer.vo.AnswerRagSnapshotVO;
import com.quanta.demo0.rag.model.RagContextDocument;
import org.springframework.stereotype.Component;

/**
 * RAG 回答文档转换器。
 *
 * <p>问题标题、问题正文和回答正文按原有顺序合并为向量化文本。问题信息由回答
 * 域的稳定快照提供，转换器不跨域访问 Mapper。</p>
 */
@Component
public class AnswerRagDocumentConverter {

    /**
     * 将回答 RAG 快照转换为上下文文档。
     *
     * @param answer 回答域稳定快照
     * @return RAG 上下文文档；输入为空时返回 {@code null}
     */
    public RagContextDocument toRagDocument(AnswerRagSnapshotVO answer) {
        if (answer == null) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        appendLine(text, answer.getQuestionTitle());
        appendLine(text, answer.getQuestionContent());
        appendWithoutTrailingLineBreak(text, answer.getContent());

        return RagContextDocument.builder()
                .contentId(answer.getQuestionId())
                .answerId(answer.getAnswerId())
                .title(answer.getQuestionTitle())
                .content(text.toString())
                .publishUserId(answer.getUserId())
                .contentType(2)
                .createTime(answer.getCreateTime())
                .build();
    }

    private void appendLine(StringBuilder target, String value) {
        if (value != null && !value.isEmpty()) {
            target.append(value).append('\n');
        }
    }

    private void appendWithoutTrailingLineBreak(StringBuilder target, String value) {
        if (value != null && !value.isEmpty()) {
            target.append(value);
        }
    }
}
