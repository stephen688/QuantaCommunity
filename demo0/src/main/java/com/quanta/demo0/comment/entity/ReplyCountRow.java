package com.quanta.demo0.comment.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReplyCountRow {
    /**
     * 父评论ID
     */
    private Long parentId;
    /**
     * 回复数量
     */
    private Long replyCount;
}
