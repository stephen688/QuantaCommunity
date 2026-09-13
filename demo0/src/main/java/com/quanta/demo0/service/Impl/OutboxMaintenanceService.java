package com.quanta.demo0.service.Impl;

import com.quanta.demo0.mapper.InboxEventMapper;
import com.quanta.demo0.mapper.OutboxEventMapper;
import com.quanta.demo0.properties.OutboxMaintenanceProperties;
import com.quanta.demo0.vo.EventStatusCountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxMaintenanceService {

    private final OutboxEventMapper outboxEventMapper;

    private final InboxEventMapper inboxEventMapper;

    private final OutboxMaintenanceProperties maintenanceProperties;

    /**
     * 每天凌晨清理已经完成且超过保留期的事件。
     * DEAD 和仍存在未完成 Inbox 的 Outbox 永远不会被自动删除。
     */
    @Transactional
    @Scheduled(cron = "${quanta.outbox.maintenance.cleanup-cron:0 30 3 * * ?}")
    public void cleanupCompletedEvents() {
        LocalDateTime before = LocalDateTime.now()
                .minusDays(maintenanceProperties.getRetentionDays());

        int deletedInbox = inboxEventMapper.deleteSuccessBefore(before);
        int deletedOutbox = outboxEventMapper.deleteCompletedBefore(before);

        if (deletedInbox > 0 || deletedOutbox > 0) {
            log.info("可靠事件清理完成，before={}, deletedInbox={}, deletedOutbox={}",
                    before, deletedInbox, deletedOutbox);
        }
    }

    /**
     * 定时记录死亡事件和最长积压时间，便于没有监控平台时直接从日志排查。
     */
    @Scheduled(fixedDelayString = "${quanta.outbox.maintenance.monitor-fixed-delay-ms:60000}")
    public void logAbnormalEventCounts() {
        long outboxDead = findCount(outboxEventMapper.countByStatus(), "DEAD");
        long inboxDead = findCount(inboxEventMapper.countByStatus(), "DEAD");
        Long longestBacklogSeconds = outboxEventMapper.selectLongestBacklogSeconds();
        long backlogSeconds = longestBacklogSeconds == null ? 0L : longestBacklogSeconds;

        if (outboxDead > 0 || inboxDead > 0
                || backlogSeconds >= maintenanceProperties.getBacklogWarningSeconds()) {
            log.warn("可靠事件存在异常，outboxDead={}, inboxDead={}, longestBacklogSeconds={}",
                    outboxDead, inboxDead, backlogSeconds);
        }
    }

    private long findCount(List<EventStatusCountVO> rows, String status) {
        return rows.stream()
                .filter(row -> status.equals(row.getStatus()))
                .map(EventStatusCountVO::getCount)
                .findFirst()
                .orElse(0L);
    }
}
