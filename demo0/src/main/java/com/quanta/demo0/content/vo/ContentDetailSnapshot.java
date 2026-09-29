package com.quanta.demo0.content.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情中与访问者无关的稳定字段。
 */
public record ContentDetailSnapshot(
        Long contentId,
        Integer contentType,
        String title,
        String content,
        Long publishUserId,
        Integer auditStatus,
        LocalDateTime createTime,
        Integer liked,
        Integer commentCount,
        Integer collectCount,
        List<String> images
) {

    public ContentDetailSnapshot {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
