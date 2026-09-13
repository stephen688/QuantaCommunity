package com.quanta.demo0.controller.admin;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.dto.InboxEventQueryDTO;
import com.quanta.demo0.dto.OutboxEventQueryDTO;
import com.quanta.demo0.entity.InboxEvent;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AdminEventService;
import com.quanta.demo0.vo.EventOverviewVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/events")
@Slf4j
public class AdminEventController {

    @Autowired
    private AdminEventService adminEventService;

    @PreAuthorize("hasAuthority('" + PermissionConstants.EVENT_READ + "')")
    @GetMapping("/overview")
    public Result<EventOverviewVO> getOverview() {
        return Result.success(
                adminEventService.getOverview()
        );
    }

    @PreAuthorize("hasAuthority('" + PermissionConstants.EVENT_READ + "')")
    @GetMapping("/outbox/page")
    public Result<PageResult> pageOutbox(OutboxEventQueryDTO query) {
        return Result.success(adminEventService.pageOutbox(query));
    }

    @PreAuthorize("hasAuthority('" + PermissionConstants.EVENT_READ + "')")
    @GetMapping("/outbox/{eventId}")
    public Result<OutboxEvent> getOutboxDetail(@PathVariable String eventId) {
        return Result.success(adminEventService.getOutboxDetail(eventId));
    }

    @AdminAudit(
            action = AdminAuditActionConstants.EVENT_REPLAY,
            targetType = "OUTBOX",
            targetId = "#eventId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.EVENT_REPLAY + "')")
    @PostMapping("/outbox/{eventId}/replay")
    public Result<Void> replayOutbox(@PathVariable String eventId) {
        log.warn("管理员重放 Outbox 事件，eventId={}", eventId);
        adminEventService.replayOutbox(eventId);
        return Result.success();
    }

    @PreAuthorize("hasAuthority('" + PermissionConstants.EVENT_READ + "')")
    @GetMapping("/inbox/page")
    public Result<PageResult> pageInbox(InboxEventQueryDTO query) {
        return Result.success(adminEventService.pageInbox(query));
    }

    @PreAuthorize("hasAuthority('" + PermissionConstants.EVENT_READ + "')")
    @GetMapping("/inbox/{consumerName}/{eventId}")
    public Result<InboxEvent> getInboxDetail(@PathVariable String consumerName, @PathVariable String eventId) {
        return Result.success(adminEventService.getInboxDetail(consumerName, eventId));
    }

    @AdminAudit(
            action = AdminAuditActionConstants.EVENT_REPLAY,
            targetType = "INBOX",
            targetId = "#eventId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.EVENT_REPLAY + "')")
    @PostMapping("/inbox/{consumerName}/{eventId}/replay")
    public Result<Void> replayInbox(@PathVariable String consumerName, @PathVariable String eventId) {
        log.warn("管理员重放 Inbox 事件，consumerName={}, eventId={}", consumerName, eventId);
        adminEventService.replayInbox(consumerName, eventId);
        return Result.success();
    }
}
