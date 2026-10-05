package com.quanta.demo0.content.enums;

/**
 * 帖子详情共享快照的可缓存状态。
 *
 * 详情两级缓存（ContentDetailCacheServiceImpl：L1 Caffeine + L2 Redis）里，
 * 每个缓存条目 = 状态 + 可选快照（见 ContentDetailCacheEntry）。
 * 状态由 ContentDetailDataLoader 查库后判定：
 *   FOUND          —— 命中：审核通过、未删除、作者信息正常，snapshot 携带可共享详情
 *   NOT_FOUND      —— selectById 查无此行（从未存在）
 *   DELETED        —— is_deleted=1（曾经存在，已软删）
 *   NOT_APPROVED   —— 审核未通过或待审核（audit_status != 1）
 *   INVALID_AUTHOR —— publish_user_id 为空的数据异常行，按脏数据挡在缓存层
 *
 * ============================================================
 * 【为什么用"枚举状态"而不是"命中返回、未命中抛异常"？】
 * ============================================================
 * 后四种都是**负结果**，它们和 FOUND 一样会被写进 L2 做负缓存（短 TTL）挡穿透：
 * 已删/未审的帖子被反复请求时，请求止步于缓存，不再打到 MySQL。
 * 抛异常的写法有两个问题：异常通道拿不到"可缓存的负结果"；
 * 且"帖子不存在"是常态流量，用异常表达常态会刷爆错误日志。
 * **业务上的"未命中"走返回值，数据访问的"出错"才走异常 —— 两者必须分开表达**。
 */
public enum ContentDetailState {
    FOUND,
    NOT_FOUND,
    DELETED,
    NOT_APPROVED,
    INVALID_AUTHOR
}
