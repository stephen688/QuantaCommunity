package com.quanta.demo0.moderation.controller.admin;

import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.moderation.dto.ModerationTargetQueryDTO;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.moderation.service.AdminModerationService;
import com.quanta.demo0.moderation.vo.ModerationRecordVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端 - AI 审核记录查询
 *
 * 定位：**只读的记录回显接口**，供后台审核详情页/列表页展示机器审核结论；
 * 真正执行"通过/驳回"的人工操作在 content/answer/comment 各自的管理接口里，
 * 不在本类职责内。所有接口统一要求 CONTENT_READ_ADMIN 权限（@PreAuthorize）。
 */
@RestController
@RequestMapping("/admin/moderation")
@Slf4j
public class AdminModerationController {

    @Autowired
    private AdminModerationService adminModerationService;

    /**
     * 查询单条最新 AI 审核记录
     * GET /admin/moderation/latest?targetType=CONTENT&targetId=1
     *
     * 【说明】记录可能不存在（如内容发布时审核被关闭且从未送审），
     * 此时服务层返回 null，接口仍以 success 包空数据返回。
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @GetMapping("/latest")
    public Result<ModerationRecordVO> latest(
            @RequestParam String targetType,
            @RequestParam Long targetId) {
        log.info("管理端查询 AI 审核记录 targetType={}, targetId={}", targetType, targetId);
        return Result.success(adminModerationService.getLatest(targetType, targetId));
    }

    /**
     * 批量查询最新 AI 审核记录，key 为 targetType:targetId
     *
     * 【边界】单次最多 50 条、按 key 去重、空/非法项直接跳过——
     * 这些限制都收在 AdminModerationServiceImpl#batchLatest 里，controller 不重复校验。
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @PostMapping("/latest/batch")
    public Result<Map<String, ModerationRecordVO>> batchLatest(
            @RequestBody List<ModerationTargetQueryDTO> queries) {
        log.info("管理端批量查询 AI 审核记录，数量={}", queries == null ? 0 : queries.size());
        return Result.success(adminModerationService.batchLatest(queries));
    }
}
