package com.quanta.demo0.content.service;

/**
 * 详情缓存失效的统一入口（写路径专用门面）。
 *
 * ============================================================
 * 【为什么不让写 Service 直接调 cache.evict？】
 * ============================================================
 * "必须等事务提交后再失效，否则提交前的读请求会把旧值回填进缓存"——
 * 这条一致性纪律若散落在每个写方法里迟早被写错，收进这一个方法
 * （afterCommit 的注册见 ContentDetailCacheInvalidatorImpl）。
 * 调用方只需表达"这个 contentId 变了，原因是 xxx"（reason 仅用于日志追踪失效来源）。
 */
public interface ContentDetailCacheInvalidator {

    /**
     * 事务提交后失效详情缓存。
     * 【契约】事务内调用 → 注册 afterCommit 回调，事务回滚则不会失效（正确：数据没变）；
     * 无事务调用 → 立即失效。不抛异常、无返回值——失效丢失由缓存 TTL 兜底。
     */
    void evictAfterCommit(Long contentId, String reason);
}
