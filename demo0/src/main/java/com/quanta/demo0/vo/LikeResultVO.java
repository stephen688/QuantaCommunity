package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞操作返回结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LikeResultVO {
    /**
     * 当前用户是否已点赞
     */
    private Boolean isLiked;

    /**
     * 当前点赞总数
     */
    private Integer likedCount;
}
