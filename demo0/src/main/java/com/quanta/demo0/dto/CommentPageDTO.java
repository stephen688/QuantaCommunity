package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentPageDTO implements Serializable {
    private Long contentId;
    private Long answerId;
    private Integer pageNum;
    private Integer pageSize;
    private Integer sortType; // 1:时间倒序 2:点赞倒序
}
