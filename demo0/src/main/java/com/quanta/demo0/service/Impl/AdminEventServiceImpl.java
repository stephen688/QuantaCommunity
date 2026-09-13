package com.quanta.demo0.service.Impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.dto.InboxEventQueryDTO;
import com.quanta.demo0.dto.OutboxEventQueryDTO;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.entity.InboxEvent;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.InboxEventMapper;
import com.quanta.demo0.mapper.OutboxEventMapper;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.service.AdminAuditRecorder;
import com.quanta.demo0.service.AdminEventService;
import com.quanta.demo0.vo.EventOverviewVO;
import com.quanta.demo0.vo.EventStatusCountVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminEventServiceImpl implements AdminEventService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OutboxEventMapper outboxEventMapper;
    private final InboxEventMapper inboxEventMapper;
    private final AdminAuditRecorder adminAuditRecorder;

    @Override
    public EventOverviewVO getOverview() {
        return EventOverviewVO.builder()
                .outboxCounts(toStatusMap(
                        outboxEventMapper.countByStatus(),
                        List.of("PENDING", "PROCESSING", "SENT", "DEAD")
                ))
                .inboxCounts(toStatusMap(
                        inboxEventMapper.countByStatus(),
                        List.of("PROCESSING", "RETRYING", "SUCCESS", "DEAD")
                ))
                .averageSendLatencyMs(outboxEventMapper.selectAverageSendLatencyMs())
                .longestBacklogSeconds(outboxEventMapper.selectLongestBacklogSeconds())
                .retryDistribution(outboxEventMapper.selectRetryDistribution())
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public PageResult pageOutbox(OutboxEventQueryDTO query) {
        validatePage(query.getPageNum(), query.getPageSize());
        validateTimeRange(query.getCreateTimeStart(), query.getCreateTimeEnd());

        // PageHelper 只拦截紧接着执行的第一条查询。
        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<OutboxEvent> page = outboxEventMapper.pageAdmin(query);

        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    public OutboxEvent getOutboxDetail(String eventId) {
        validateEventId(eventId);

        // 详情接口才读取 payload，列表接口不返回大 JSON。
        OutboxEvent event = outboxEventMapper.selectByEventId(eventId);

        if (event == null) {
            throw new ContentFailedException("Outbox 事件不存在");
        }

        return event;
    }

    @Override
    @Transactional
    public void replayOutbox(String eventId) {
        validateEventId(eventId);

        Long adminId = BaseContext.getCurrentId();
        LocalDateTime now = LocalDateTime.now();

        // SQL 带 status='DEAD'，所以正在处理的事件不能被重放。
        int updatedRows = outboxEventMapper.replayDead(eventId, adminId, now);

        if (updatedRows != 1) {
            throw new ContentFailedException("只有 DEAD 状态的 Outbox 事件可以重放");
        }

        // 审计：Outbox 事件重放成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.EVENT_REPLAY,
                "OUTBOX",
                eventId,
                "outboxStatus=DEAD",
                "outboxStatus=PENDING"
        );
    }

    @Override
    public PageResult pageInbox(InboxEventQueryDTO query) {
        validatePage(query.getPageNum(), query.getPageSize());
        validateTimeRange(query.getCreateTimeStart(), query.getCreateTimeEnd());

        PageHelper.startPage(query.getPageNum(), query.getPageSize());
        Page<InboxEvent> page = inboxEventMapper.pageAdmin(query);

        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    public InboxEvent getInboxDetail(String consumerName, String eventId) {
        validateConsumerName(consumerName);
        validateEventId(eventId);

        InboxEvent event = inboxEventMapper.selectByConsumerAndEvent(consumerName, eventId);

        if (event == null) {
            throw new ContentFailedException("Inbox 事件不存在");
        }

        return event;
    }

    @Override
    @Transactional
    public void replayInbox(String consumerName, String eventId) {
        validateConsumerName(consumerName);
        validateEventId(eventId);

        Long adminId = BaseContext.getCurrentId();
        LocalDateTime now = LocalDateTime.now();

        // 第一步：只有 DEAD Inbox 能抢到重放权。
        int inboxRows = inboxEventMapper.replayDead(consumerName, eventId, adminId, now);

        if (inboxRows != 1) {
            throw new ContentFailedException("只有 DEAD 状态的 Inbox 事件可以重放");
        }

        // 第二步：让原 Outbox 使用相同 eventId 再发送一次。
        int outboxRows = outboxEventMapper.resetForInboxReplay(eventId, adminId, now);

        if (outboxRows != 1) {
            // RuntimeException 会让上面的 Inbox 修改一起回滚。
            throw new ContentFailedException("对应的 Outbox 事件不存在或不能重放");
        }

        // 审计：Inbox 事件重放成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.EVENT_REPLAY,
                "INBOX",
                eventId,
                "inboxStatus=DEAD",
                "inboxStatus=PENDING"
        );
    }

    private void validatePage(Integer pageNum, Integer pageSize) {
        if (pageNum == null || pageNum < 1) {
            throw new ContentFailedException("pageNum 不能小于 1");
        }

        if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ContentFailedException("pageSize 必须在 1 到 100 之间");
        }
    }

    private void validateEventId(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            throw new ContentFailedException("eventId 不能为空");
        }
    }

    private void validateConsumerName(String consumerName) {
        if (!StringUtils.hasText(consumerName)) {
            throw new ContentFailedException("consumerName 不能为空");
        }
    }

    private void validateTimeRange(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new ContentFailedException("开始时间不能晚于结束时间");
        }
    }

    private Map<String, Long> toStatusMap(
            List<EventStatusCountVO> rows,
            List<String> statuses
    ) {
        Map<String, Long> result = new LinkedHashMap<>();

        for (String status : statuses) {
            result.put(status, 0L);
        }

        for (EventStatusCountVO row : rows) {
            result.put(row.getStatus(), row.getCount());
        }

        return result;
    }
}
