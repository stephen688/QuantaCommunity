// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/enums/ModerationTargetType.java
package com.quanta.demo0.moderation.enums;

/**
 * 审核目标类型：帖子/回答/评论。
 * 与 yml 的 quanta.moderation.targets.content|answer|comment 三个键、
 * 消费工作流里三个 dispatch 分支（content/answer/comment 包的 AuditService）一一对应。
 */
public enum ModerationTargetType {
    CONTENT,   // 帖子
    ANSWER,    // 回答
    COMMENT    // 评论
}