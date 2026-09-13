// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/enums/ModerationDisabledPolicy.java
package com.quanta.demo0.annotation;

public enum ModerationDisabledPolicy {
    APPROVED,  // 关闭 AI 时，敏感词通过后直接通过
    PENDING    // 关闭 AI 时，保留待审交给人工审核
}