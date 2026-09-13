package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端 AI 审核记录视图（不含 rawResponse）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModerationRecordVO {

    private String targetType;
    private Long targetId;
    private String provider;
    private String decision;
    private String riskLevel;
    private String labels;
    private String rejectReason;
    private String taskStatus;
    private Integer retryCount;
    private LocalDateTime updateTime;
}
