package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AnswerLiked {

    /**
     * 点赞唯一 ID（主键）
     */
    private Long id;

    /**
     * 回答唯一 ID
     */
    private Long answerId;

    /**
     * 点赞用户 ID（关联 tb_user.user_id）
     */
    private Long userId;

    /**
     * 点赞时间
     */
    private LocalDateTime createTime;
}