package com.quanta.demo0.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 回答 DTO
 * 【用途】用于接收前端提交的专业区回答数据
 * 【字段说明】
 * - questionId: 问题 ID（必填，关联 tb_content.content_id）
 * - content: 回答正文（必填，支持长文本）
 * @author Quanta Team
 * @since 2026-05-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 问题 ID（必填）
     */
    private Long questionId;

    /**
     * 回答正文（必填）
     */
    private String content;
}