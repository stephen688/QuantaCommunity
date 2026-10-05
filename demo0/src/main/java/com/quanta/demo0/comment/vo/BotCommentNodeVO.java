package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** bot 视角的评论节点，字段名与 QuantaBot CommentNode 契约一致。 */
/**
 * 【为什么与实体分开、字段这么少？】
 * 这是跨服务契约（C-2）：QuantaBot 组织回答只需要"这条评论是谁、说了什么、
 * 在楼层里的位置、什么时候说的"。审核字段（auditStatus/rejectReason）、
 * 计数字段（likeCount）、软删标记（isDeleted）一概不外泄——**给外部系统的
 * 视图按"最小必要"裁剪，而不是把内部读模型整个递出去**。
 * 由 BotCommentServiceImpl.toNodes 统一装配，三个 bot 读接口（chain/tree/history）
 * 共用同一节点形状，对端只需解析一种结构。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 以下字段名是对端 Pydantic 模型的镜像：改名 = 跨语言契约变更，须两边同步。
    private Long commentId;
    // 楼层归属：服务端已把 null/0 归一成 null（normalizeParentId），对端拿到的
    // 一级评论此字段恒为 null，不再需要自己兼容 0。
    private Long parentId;
    // 被回复对象：null 表示不是楼内回复；对端可据此重建"回复 @某人"关系。
    private Long replyCommentId;
    private Long userId;
    private String content;
    // 无图时是空数组而非 null（toNodes 里 getOrDefault(..., List.of())），
    // 与 BotMentionMessage.commentImages 的契约口径一致，对端不必判空。
    private List<String> images;
    // 字符串而非 LocalDateTime：跨语言序列化里时间格式最容易漂移，
    // 服务端固定按 "yyyy-MM-dd HH:mm:ss" 格式化（BotCommentServiceImpl.DATE_TIME_FORMATTER），
    // 契约里连格式一起冻结；时间为 null 时给空串。
    private String createTime;
}
