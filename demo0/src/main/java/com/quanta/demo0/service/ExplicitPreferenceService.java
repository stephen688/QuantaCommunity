package com.quanta.demo0.service;

import com.quanta.demo0.feed.dto.BotProfileEventDTO;

/** 显式画像业务边界：认证在 HTTP 门面，事实与 Outbox 同事务，异步校准派生 Redis。 */
public interface ExplicitPreferenceService {
    /** 新事件返回 true，同 ID 同内容返回 false；冲突或无效输入抛业务参数错误。 */
    boolean accept(BotProfileEventDTO event);
    /** 从当前事实重建，覆盖式写入可反复执行；外部失败交由 Inbox 重试。 */
    void reconcile(Long userId);
}
