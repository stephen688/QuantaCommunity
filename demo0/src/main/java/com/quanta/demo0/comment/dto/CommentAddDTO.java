package com.quanta.demo0.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.io.Serializable;
import java.util.List;

/**
 * 评论添加 DTO
 * 用于接收前端提交的评论数据
 *
 * ============================================================
 * 【这个 DTO 里为什么没有 userId 字段？】
 * ============================================================
 * 评论者身份不在请求体里，服务端在 sendComment 第 1 步从 token 解析结果
 * BaseContext.getCurrentId() 取——**凡是"客户端可以给"的身份字段都是漏洞**：
 * 攻击者改一下 JSON 就能冒充任何人发评论。同理审核状态、点赞数也不在
 * 这里，入库时由服务端写死（PENDING / 0）。
 * 除身份外的层级字段（parentId / replyCommentId / replyUserId）虽然允许
 * 客户端传，但服务端会做归属校验：父评论必须存在且同级、被回复评论必须
 * 同内容同楼层（sendComment 第 2/4 步），传错只会得到 400，不会污染数据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentAddDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属内容 ID（帖子 ID）
     * 必填
     */

    private Long contentId;

    /**
     * 回答 ID（专业问答区使用，生活区传 null）
     * 可选
     */
    private Long answerId;

    /**
     * 父评论 ID
     * 一级评论传 null，二级评论传对应一级评论 ID
     * 必填
     */

    private Long parentId;

    /**
     * 被回复的评论 ID
     * 回复二级评论时传，一级评论不传
     * 可选
     */
    private Long replyCommentId;

    /**
     * 被回复的用户 ID
     * 可选
     */
    private Long replyUserId;

    /**
     * 评论内容
     * 必填，最大 500 字
     */
    // 真实上限按内容所在分区区分：生活区 500 / 专业区 1000
    //（CommentZonePolicy.getMaxLength，sendComment 第 1 步校验），
    // 另有敏感词检查（SensitiveWordChecker.findFirstHit），命中直接拒绝。

    private String content;

    /**
     * 评论配图 URL 列表
     * 可选，最多 5 张图片
     */
    // 限额同样分区：生活区 5 张 / 专业区 1 张（CommentZonePolicy.getMaxImages）。
    // 逐条必须是 http:// 或 https:// 前缀，空串会被静默过滤；
    // URL 还受 CommentEventProducer 的 4096 字节/条限制（审核 payload 体积约束）。

    private List<String> imageUrls;

    /**
     * 是否通过 @ 卡片提及 bot（C-4 前端结构化标记）。
     * 可选；服务端事件判定以文本 @昵称 + replyUserId 为准，本字段仅作观测记录。
     */
    // sendComment 对它只打一条 info 日志（"仅作观测留痕"），不参与任何业务判断；
    // 真正的 bot 触发判定在审核通过后由 BotMentionDetector 完成。

    private Boolean mentionBot;

}
