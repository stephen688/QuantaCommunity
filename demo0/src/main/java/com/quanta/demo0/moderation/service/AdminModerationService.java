package com.quanta.demo0.moderation.service;

import com.quanta.demo0.moderation.dto.ModerationTargetQueryDTO;
import com.quanta.demo0.moderation.vo.ModerationRecordVO;

import java.util.List;
import java.util.Map;

/**
 * 管理端机审记录查询服务（只读）：给人工审核台提供"AI 当时怎么判的"这份证据。
 *
 * ============================================================
 * 【它在机审链路里的位置——MANUAL 与失败兜底的另一半】
 * ============================================================
 * 机审给出 MANUAL（疑似转人工）、或重试耗尽留下 ERROR/FAILED 记录后，
 * 消息侧都不再推进业务状态；此时人工裁决依赖的机审证据就靠本服务暴露：
 * AdminModerationController 的 /admin/moderation/latest 与 /latest/batch
 * 直接透传到这里。**机审不改状态时，这条记录就是转人工的全部依据**。
 * 人工通过/驳回的写操作不在这里，走 content 包的 AdminContentService。
 */
public interface AdminModerationService {

    /** 查询单个目标（帖子/回答/评论）的最新一次机审记录，用于审核详情页；无记录返回 null。 */
    ModerationRecordVO getLatest(String targetType, Long targetId);

    /** 批量查询最新机审记录，返回按 "targetType:targetId" 键组织的 Map，用于列表页；单次上限见实现类 BATCH_MAX。 */
    Map<String, ModerationRecordVO> batchLatest(List<ModerationTargetQueryDTO> queries);
}
