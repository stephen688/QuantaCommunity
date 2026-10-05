package com.quanta.demo0.content.vo;

import com.quanta.demo0.content.enums.ContentDetailState;

/**
 * L1/L2 详情缓存条目，负结果也通过 state 持久化，避免反复打到数据库。
 *
 * ============================================================
 * 【state 和 snapshot 绑在一起：缓存的是"查询结论"，不只是"数据"】
 * ============================================================
 * FOUND 时 snapshot 非空；NOT_FOUND/DELETED/NOT_APPROVED/INVALID_AUTHOR 时
 * snapshot 为 null —— 但这个"null"本身被序列化进 Redis 短暂保存（负缓存）。
 * 价值：爬虫反复戳已删帖/未过审帖，L2 直接用缓存的负结论应答，DB 零压力
 * （防穿透的落点，TTL 策略见 ContentDetailCacheServiceImpl.ttlSeconds）。
 *
 * 【为什么也做成 record】和 ContentDetailSnapshot 同理 —— 缓存条目必须不可变，
 * state + snapshot 两字段 final，L1 多线程读、L2 跨实例 JSON 往返都安全。
 * 字段构成刻意最小：见 ContentDetailCacheServiceImpl 类注释的两级缓存全貌。
 */
public record ContentDetailCacheEntry(
        ContentDetailState state,
        ContentDetailSnapshot snapshot
) {
}
