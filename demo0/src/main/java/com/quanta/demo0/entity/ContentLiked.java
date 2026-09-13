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
public class ContentLiked {

    /**
     * 点赞唯一 ID（主键）
     */
    private Long id;

    /**
     * 内容唯一 ID（主键）
     */
    private Long contentId;

    /**
     * 点赞用户 ID（关联 tb_user.user_id）
     */
    private Long userId;

    /**
     * 点赞时间
      */
    private LocalDateTime createTime;

}
