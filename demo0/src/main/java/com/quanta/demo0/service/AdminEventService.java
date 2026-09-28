package com.quanta.demo0.service;

import com.quanta.demo0.platform.mq.admin.dto.InboxEventQueryDTO;
import com.quanta.demo0.platform.mq.admin.dto.OutboxEventQueryDTO;
import com.quanta.demo0.platform.mq.entity.InboxEvent;
import com.quanta.demo0.platform.mq.entity.OutboxEvent;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.mq.admin.vo.EventOverviewVO;

public interface AdminEventService {

    EventOverviewVO getOverview();

    PageResult pageOutbox(OutboxEventQueryDTO query);

    OutboxEvent getOutboxDetail(String eventId);

    void replayOutbox(String eventId);

    PageResult pageInbox(InboxEventQueryDTO query);

    InboxEvent getInboxDetail(String consumerName, String eventId);

    void replayInbox(String consumerName, String eventId);
}
