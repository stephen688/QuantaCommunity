package com.quanta.demo0.moderation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询单条 AI 审核记录
 *
 * 批量接口（POST /admin/moderation/latest/batch）的请求体就是本 DTO 的 List，
 * 服务层限单次最多 50 条、按 "targetType:targetId" 去重（见 AdminModerationServiceImpl）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ModerationTargetQueryDTO {

    /** CONTENT / ANSWER / COMMENT */
    // 服务层会 trim + 转大写，传小写也能查
    private String targetType;

    /** 帖子/回答/评论 ID */
    private Long targetId;
}
