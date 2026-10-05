package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** bot 视角的帖子主楼摘要（C-2①）。 */
/**
 * 【为什么存在：Bot 消费"主楼"只需要四个字段】
 * QuantaBot 在评论链路里要读帖子原文做回答，postId/userId/title/content 就够了 ——
 * 计数、审核状态、访问者状态一概不给。给外部 Bot 的数据按"最小必要"裁剪，
 * 而不是把内部读模型整个递出去（对比用户侧的 ContentVO：字段按页面需求裁剪）。
 * 它作为 BotCommentChainVO.post 嵌在评论链路响应里，由 comment 域经
 * BotCommentServiceImpl 装配 —— 字段只出不进，客户端传值无意义。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotPostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 帖子 ID（即 contentId，Bot 侧用它回链/去重） */
    private Long postId;

    /** 发布者用户 ID（Bot 用于区分"回答谁的问题"，不承担权限含义） */
    private Long userId;

    /** 帖子标题 */
    private String title;

    /** 帖子正文（主楼内容，不含评论与回答） */
    private String content;
}
