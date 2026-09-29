package com.quanta.demo0.content.vo;

import com.quanta.demo0.content.enums.ContentDetailState;

/**
 * L1/L2 详情缓存条目，负结果也通过 state 持久化，避免反复打到数据库。
 */
public record ContentDetailCacheEntry(
        ContentDetailState state,
        ContentDetailSnapshot snapshot
) {
}
