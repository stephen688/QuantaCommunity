package com.quanta.demo0.answer.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回答域向 RAG 向量同步暴露的稳定快照。
 *
 * <p>该 VO 只包含回答向量化所需字段及其所属问题文本，RAG 层不需要依赖
 * {@code QuestionMapper} 或 {@code ContentMapper}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerRagSnapshotVO {

    private Long answerId;
    private Long questionId;
    private Long userId;
    private String questionTitle;
    private String questionContent;
    private String content;
    private Integer auditStatus;
    private Integer isDeleted;
    private LocalDateTime createTime;
}
