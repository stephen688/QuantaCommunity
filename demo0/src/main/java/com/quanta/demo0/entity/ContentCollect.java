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
public class ContentCollect {

    /**
     * 收藏唯一 ID（主键）
     */
    private Long id;

    /**
     * 内容唯一 ID（主键）
     */
    private Long contentId;

    /**
     * 收藏用户 ID（关联 tb_user.user_id）
     */
    private Long userId;

    /**
     * 收藏时间
     */
    private LocalDateTime createTime;

}