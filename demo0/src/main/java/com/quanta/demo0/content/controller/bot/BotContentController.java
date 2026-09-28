package com.quanta.demo0.content.controller.bot;

import com.quanta.demo0.platform.security.annotation.RateLimit;
import com.quanta.demo0.platform.security.constant.RoleConstants;
import com.quanta.demo0.content.vo.BotSyncPageVO;
import com.quanta.demo0.content.dto.BotPolicyDocDTO;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.content.service.BotContentSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** bot 内容同步与政策文档接口（C-3）。 */
@RestController
@RequestMapping("/bot")
@RequiredArgsConstructor
public class BotContentController {

    private final BotContentSyncService botContentSyncService;


    @GetMapping("/content/sync")
    @PreAuthorize("hasRole('BOT')")
    @RateLimit(scene = "bot-read", limit = 120, windowSeconds = 60, failClosed = false)
    public Result<BotSyncPageVO> sync(
            @RequestParam String since,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.success(botContentSyncService.getSync(since, pageNum, pageSize));
    }

    @PostMapping("/knowledge/policy-docs")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @RateLimit(scene = "bot-policy-write", limit = 20, windowSeconds = 60)
    public Result<Void> upsertPolicyDoc(@RequestBody BotPolicyDocDTO dto) {
        botContentSyncService.upsertPolicyDoc(dto);
        return Result.success();
    }

    @DeleteMapping("/knowledge/policy-docs/{docId}")
    @PreAuthorize("hasRole('" + RoleConstants.OPERATIONS_ADMIN + "')")
    @RateLimit(scene = "bot-policy-write", limit = 20, windowSeconds = 60)
    public Result<Void> deletePolicyDoc(@PathVariable String docId) {
        botContentSyncService.softDeletePolicyDoc(docId);
        return Result.success();
    }
}
