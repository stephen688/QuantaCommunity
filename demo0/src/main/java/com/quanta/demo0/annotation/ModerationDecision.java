// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/enums/ModerationDecision.java
package com.quanta.demo0.annotation;

public enum ModerationDecision {
    PASS,    // 通过
    REJECT,  // 驳回
    MANUAL,  // 疑似，转人工
    ERROR    // 错误，需重试
}