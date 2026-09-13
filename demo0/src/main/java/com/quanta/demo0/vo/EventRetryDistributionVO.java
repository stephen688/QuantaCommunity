package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventRetryDistributionVO {

    private Integer retryCount;

    private Long eventCount;
}
