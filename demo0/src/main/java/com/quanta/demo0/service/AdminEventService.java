package com.quanta.demo0.service;

import com.quanta.demo0.dto.InboxEventQueryDTO;
import com.quanta.demo0.dto.OutboxEventQueryDTO;
import com.quanta.demo0.entity.InboxEvent;
import com.quanta.demo0.entity.OutboxEvent;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.vo.EventOverviewVO;

public interface AdminEventService {

    EventOverviewVO getOverview();

    PageResult pageOutbox(OutboxEventQueryDTO query);

    OutboxEvent getOutboxDetail(String eventId);

    void replayOutbox(String eventId);

    PageResult pageInbox(InboxEventQueryDTO query);

    InboxEvent getInboxDetail(String consumerName, String eventId);

    void replayInbox(String consumerName, String eventId);
}
