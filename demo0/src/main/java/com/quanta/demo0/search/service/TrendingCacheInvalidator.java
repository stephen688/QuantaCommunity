package com.quanta.demo0.search.service;

/** 搜索热榜缓存失效端口，供产生热榜事实变更的领域在提交后调用。 */
public interface TrendingCacheInvalidator {
    /*
     * @param reason 失效原因，仅用于日志定位（如 content-delete、admin-content-audit）
     */
    /** 注册提交后失效；无事务时立即失效。 */
    void evictAfterCommit(String reason);
}
