package com.quanta.demo0.content.service;

import com.quanta.demo0.content.vo.ContentDetailCacheEntry;

import java.util.function.Supplier;

/**
 * 帖子详情两级缓存端口（L1 Caffeine 本地缓存 + L2 Redis，含负缓存与墓碑失效）。
 *
 * ============================================================
 * 【接口为什么长这样——loader 由调用方注入】
 * ============================================================
 * 缓存组件不该知道"怎么查数据库"：getOrLoad 只承诺"给你最快可用的结果"，
 * miss 时的回源逻辑（组装快照还是判定负状态）由读服务注入。
 * 调用方：ContentQueryServiceImpl（读路径）、ContentDetailCacheInvalidator（写路径失效）。
 * 穿透/击穿/雪崩三板斧与墓碑 CAS 的完整设计讲解见 ContentDetailCacheServiceImpl 类注释。
 */
public interface ContentDetailCacheService {

    /**
     * 读入口：L1 → L2 → loader 回源，返回的 entry 可能是负状态（NOT_FOUND/DELETED 等）。
     * 【契约】"内容不存在"也会被短 TTL 缓存（负缓存防穿透），
     * 由调用方把负状态翻译成业务异常；loader 抛出的异常原样透传（不吞业务异常），
     * 缓存故障时自动降级为直查 DB。
     * @param contentId 内容 ID
     * @param loader    缞源逻辑（组装快照还是判定负状态）
     * @return 内容详情缓存条目
     */
    ContentDetailCacheEntry getOrLoad(Long contentId, Supplier<ContentDetailCacheEntry> loader);


    /**
     * 失效入口（写路径调用）：清本实例 L1 + 在 L2 写墓碑（不是 DELETE）。
     * 【契约】其它实例的 L1 靠短 TTL 自然过期；L2 写墓碑失败只告警不抛
     * （缓存异常不得传染业务主流程），脏数据靠 TTL 自愈。
     */
    void evict(Long contentId);
}
