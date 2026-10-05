// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/service/ContentModerationService.java
package com.quanta.demo0.moderation.service;
import com.quanta.demo0.moderation.result.ModerationResult;
import com.quanta.demo0.moderation.mq.message.ModerationTaskMessage;

/**
 * 机审核心门面：一次 {@link #moderate} = 开关判定 + 指纹防重 + 阿里云双模态审核 + 记录落库。
 *
 * ============================================================
 * 【谁在调它，同步还是异步？】
 * ============================================================
 * 调用方是消费侧的 ModerationWorkflowServiceImpl（MQ 消费线程内同步执行），
 * 不是发布线程——发布侧只往 Outbox 登记一条 MODERATION_REQUESTED 事件
 * （见 ContentEventProducer.createContentModerationEvent），机审本身
 * 发生在审核消息被消费时。**调云 API 的秒级耗时被 MQ 隔离，不会拖慢用户发布。**
 * 敏感词初筛不在这里：它在更早的发布入口（各 CommandService 用 SensitiveWordChecker 拦截），
 * 到达本接口的内容已经过本地词库第一道关。
 */
public interface ContentModerationService {

    /**
     * 执行审核任务
     * @param task 审核任务消息
     * @return 审核结果
     *
     * 【坑】返回 PASS 的来路不止一种：总开关关闭、目标类型 disabled-policy=APPROVED、
     * 指纹复用命中历史 PASS、真机审通过——调用方无法也不需要区分，
     * 但排查"为什么这条内容没走云审"时要对这几种短路有数（详见实现类注释）。
     */
    ModerationResult moderate(ModerationTaskMessage task);

    /**
     * 重试耗尽后落库 ERROR + FAILED，供管理端人工兜底
     * （唯一调用方：ModerationWorkflowServiceImpl.handleProcessingFailure；
     * 落 FAILED 记录本身不触发重试，只留下人工排查的证据）
     */
    void saveFailedRecord(ModerationTaskMessage task, ModerationResult result);
}