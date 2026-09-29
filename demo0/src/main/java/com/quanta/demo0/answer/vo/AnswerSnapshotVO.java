package com.quanta.demo0.answer.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 回答域事实快照；供互动、审核和搜索校验，不包含访问者状态。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerSnapshotVO {
    private Long answerId;
    private Long questionId;
    private Long userId;
    private String content;
    private Integer likeCount;
    private Integer commentCount;
    private Integer isAccepted;
    private Integer auditStatus;
    private Integer isDeleted;
    private LocalDateTime createTime;
}
