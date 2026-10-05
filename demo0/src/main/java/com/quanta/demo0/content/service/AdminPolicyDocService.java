package com.quanta.demo0.content.service;

import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.content.dto.PolicyDocAdminQueryDTO;
import com.quanta.demo0.content.vo.PolicyDocAdminVO;
import com.quanta.demo0.platform.common.result.PageResult;

/**
 * 政策知识库管理端服务。
 *
 * <p>查询面向运营后台，写入统一委托 BotContentSyncService；本服务不直接操作 Bot 下游或向量库。</p>
 */
public interface AdminPolicyDocService {

    /** 分页查询源文档，支持关键词和 active/deleted 筛选。 */
    PageResult pageQuery(PolicyDocAdminQueryDTO query);

    /** 查询源文档详情，包含软删墓碑。 */
    PolicyDocAdminVO getDetail(String docId);

    /** 新增、更新或恢复一个政策源文档。 */
    void upsert(BotPolicyDocDTO dto);

    /** 软删除一个政策源文档并留下可同步墓碑。 */
    void softDelete(String docId);
}
