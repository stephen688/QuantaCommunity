package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询单条 AI 审核记录
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModerationTargetQueryDTO {

    /** CONTENT / ANSWER / COMMENT */
    private String targetType;

    private Long targetId;
}
