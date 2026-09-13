// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/service/ContentModerationService.java
package com.quanta.demo0.service;
import com.quanta.demo0.modertion.result.ModerationResult;
import com.quanta.demo0.mq.message.ModerationTaskMessage;

public interface ContentModerationService {

    /**
     * 执行审核任务
     * @param task 审核任务消息
     * @return 审核结果
     */
    ModerationResult moderate(ModerationTaskMessage task);

    /**
     * 重试耗尽后落库 ERROR + FAILED，供管理端人工兜底
     */
    void saveFailedRecord(ModerationTaskMessage task, ModerationResult result);
}