// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/enums/ModerationDecision.java
package com.quanta.demo0.moderation.enums;

/**
 * 机审结论四态。两个云客户端（AliyunText/ImageModerationClient）把 SDK 返回
 * 翻译成这里的值，落库存 name() 字符串（tb_moderation_record.decision）。
 *
 * 【易误解】ERROR 不是"内容违规"，而是"这轮没审成"（接口异常/状态码不对），
 * 消费工作流会据此走重试直至转 DEAD；PASS/REJECT/MANUAL 才是对内容本身的裁决
 * （分别对应工作流里的 approve / reject / 转人工，见 ModerationWorkflowServiceImpl）。
 */
public enum ModerationDecision {
    PASS,    // 通过
    REJECT,  // 驳回
    MANUAL,  // 疑似，转人工
    ERROR    // 错误，需重试
}