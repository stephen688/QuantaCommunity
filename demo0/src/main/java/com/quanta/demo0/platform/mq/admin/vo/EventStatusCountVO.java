package com.quanta.demo0.platform.mq.admin.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventStatusCountVO {

    private String status;

    private Long count;
}
