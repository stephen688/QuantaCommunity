package com.quanta.demo0.content.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情中与访问者无关的稳定字段。
 *
 * ============================================================
 * 【为什么是 record（不可变），而其它 VO 用 Lombok @Data】
 * ============================================================
 * 这个对象要被放进 L1（Caffeine，本 JVM 多线程共享）和 L2（Redis JSON，跨实例共享）——
 * **缓存条目必须是不可变的**：如果某个请求能 set 到快照字段，就是把脏数据写进了
 * 所有用户共享的缓存。record 天然无 setter + final 字段 + 值语义，是缓存条目的正确默认。
 *
 * 【紧凑构造器里的防御性拷贝是最后一道闸】
 * images = List.copyOf(images)：调用方（loader）手里的 List 如果被后续代码改了，
 * 不能波及缓存里的这份。进缓存的对象，出构造器那一刻起谁也改不了 ——
 * 看 ContentDetailDataLoader 的"进快照/不进快照"标准：
 * 这里只放"两个用户请求会相同"的字段，isLiked/avatarUrl 一律不进。
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
