package com.quanta.demo0.service;

import com.quanta.demo0.content.vo.BotSyncPageVO;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;

/** bot 内容源同步与政策文档管理（C-3）。 */
public interface BotContentSyncService {

    BotSyncPageVO getSync(String since, int pageNum, int pageSize);

    void upsertPolicyDoc(BotPolicyDocDTO dto);

    void softDeletePolicyDoc(String docId);
}
