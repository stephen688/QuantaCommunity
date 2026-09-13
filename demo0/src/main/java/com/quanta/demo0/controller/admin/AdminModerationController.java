package com.quanta.demo0.controller.admin;

import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.dto.ModerationTargetQueryDTO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AdminModerationService;
import com.quanta.demo0.vo.ModerationRecordVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理端 - AI 审核记录查询
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
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.CONTENT_READ_ADMIN + "')")
    @PostMapping("/latest/batch")
    public Result<Map<String, ModerationRecordVO>> batchLatest(
            @RequestBody List<ModerationTargetQueryDTO> queries) {
        log.info("管理端批量查询 AI 审核记录，数量={}", queries == null ? 0 : queries.size());
        return Result.success(adminModerationService.batchLatest(queries));
    }
}
