
package com.quanta.demo0.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 处理评论举报入参
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentReportHandleDTO {
    /**
     * 举报记录 ID
     */
    private Long reportId;

    /**
     * 处理结果：1-删除评论 2-警告用户 3-删除评论 + 警告用户 4-驳回举报
     */
    private Integer handleResult;

    /**
     * 处理备注（可选）
     */
    private String handleRemark;
}