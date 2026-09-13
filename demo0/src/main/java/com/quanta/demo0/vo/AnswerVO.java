
package com.quanta.demo0.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 回答 VO
 * <p>
 * 【用途】用于向前端返回回答数据
 *
 * @author Quanta Team
 * @since 2026-05-01
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 回答 ID
     */
    private Long answerId;

    /**
     * 问题 ID
     */
    private Long questionId;

    /**
     * 回答用户 ID
     */
    private Long userId;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户头像
     */
    private String avatarUrl;

    /**
     * 用户届数
     */
    private String quantaBatch;

    /**
     * 回答正文
     */
    private String content;

    /**
     * 点赞数
     */
    private Integer likeCount;

    /**
     * 评论数
     */
    private Integer commentCount;

    /**
     * 是否被采纳（0-未采纳 1-已采纳）
     */
    private Integer isAccepted;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     */
    private Integer auditStatus;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
}