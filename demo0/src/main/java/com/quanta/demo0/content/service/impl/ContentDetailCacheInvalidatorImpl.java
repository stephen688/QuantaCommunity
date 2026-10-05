package com.quanta.demo0.content.service.impl;

import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.service.ContentDetailCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 详情缓存失效器 —— 写路径与缓存之间的"翻译层"。
 *
 * ============================================================
 * 【为什么包一层 Invalidator，不让写 Service 直接调 cache.evict？】
 * ============================================================
 * 1. **语义封装**：写代码的人只需要表达"这个 contentId 变了"，至于
 *    "要先等事务提交""没有事务时怎么办"这些一致性纪律，全部收在这一个类里。
 *    十几个写路径（发布/删帖/审核/计数变更……）每处一行 evictAfterCommit，
 *    规则不会写散。
 *
 * 2. **afterCommit 的统一入口**：直接在事务里删缓存是经典错误 ——
 *    删完缓存、事务还没提交，读请求立刻回源查到**旧值**并回填缓存，
 *    之后事务才提交 —— 缓存里躺着旧值，且没人再触发失效（脏到 TTL 为止）。
 *    所以失效必须挂在 afterCommit：**先让事实源落定，再清投影**。
 *
 * 【面试追问：afterCommit 回调抛异常会怎样？】
 * 事务已经提交，异常只能被日志吃掉（这里没抛出去）—— 所以缓存失效的
 * 实现内部必须自己 try-catch（见 ContentDetailCacheServiceImpl.evict）。
 * 这也是为什么失效丢了要靠 TTL 兜底设计成"可自愈"。
 *
 * 【面试追问：没有事务的分支为什么直接同步删？】
 * 管理端/内部调用可能不在事务里，此时"等提交"没有意义，直接删即可。
 * 用 isSynchronizationActive 双重判断是因为部分场景事务已存在
 * 但同步器未激活（如 propagation=NOT_SUPPORTED 穿透后）。
 */
@Component
@Slf4j
public class ContentDetailCacheInvalidatorImpl implements ContentDetailCacheInvalidator {

    private final ContentDetailCacheService contentDetailCacheService;

    public ContentDetailCacheInvalidatorImpl(ContentDetailCacheService contentDetailCacheService) {
        this.contentDetailCacheService = contentDetailCacheService;
    }

    /**
     * 失效的唯一入口：有事务 → 注册 afterCommit 回调后再删；无事务 → 立即删。
     * 顺序不能反：**先让事实源（DB 事务）落定，再清缓存投影**，否则读请求会在
     * 提交前回源把旧值填回缓存（推导见类注释）。
     *
     * 【reason 只进日志】每个写路径传自己的失效原因（发布/删帖/审核通过…），
     * 出现"改了没生效"的投诉时，能凭这行日志反查是哪条写路径漏了失效 ——
     * 可观测性是缓存一致性方案的配套设施，不是可选项。
     */
    @Override
    public void evictAfterCommit(Long contentId, String reason) {
        if (contentId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evict(contentId, reason);
                        }
                    }
            );
            return;
        }
        evict(contentId, reason);
    }

    /** 真正执行失效：委托 ContentDetailCacheServiceImpl.evict（清本实例 L1 + 在 L2 写墓碑），这里只补一条带原因的审计日志。 */
    private void evict(Long contentId, String reason) {
        contentDetailCacheService.evict(contentId);
        log.info("帖子详情缓存失效完成，contentId={}，reason={}", contentId, reason);
    }
}
