package com.quanta.demo0.service;

public interface ContentAuditService {

    /**
     * 审核通过：更新状态并执行曝光副作用(如发 Feed、写 ES 等)
     */
    void approveContent(Long contentId);

    /**
     * 审核驳回：仅当仍为待审时更新状态并通知作者
     */
    void rejectContent(Long contentId, String rejectReason);
}
