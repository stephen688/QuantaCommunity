package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventOverviewVO {

    private Map<String, Long> outboxCounts;

    private Map<String, Long> inboxCounts;

    private Double averageSendLatencyMs;

    private Long longestBacklogSeconds;

    private List<EventRetryDistributionVO> retryDistribution;

    private LocalDateTime generatedAt;
}
