package com.quanta.demo0.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum InboxEventStatus {

    PROCESSING("PROCESSING"),
    RETRYING("RETRYING"),
    SUCCESS("SUCCESS"),
    DEAD("DEAD");

    private final String code;
}