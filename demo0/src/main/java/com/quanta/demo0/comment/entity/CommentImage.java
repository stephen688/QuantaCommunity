package com.quanta.demo0.comment.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论图片实体类
 * 对应数据库表：tb_comment_image
 *
 * ============================================================
 * 【为什么图片单独一张表，而不是在评论表里存个 JSON 数组？】
 * ============================================================
 * 三个真实消费场景都按 comment_id 单独查图片：AI 图片审核取 URL 列表
 * （CommentMapper.selectImagesByCommentId，"图片审核兜底"）、C 端与 bot 端
 * 装配评论节点图片（selectImagesByCommentIds 一次批量取）。独立成表才能
 * **批量 join / in 查询、单独维护 sort 顺序**，塞进评论表 JSON 则每次都要
 * 整行取回再解析。
 *
 * 【删除语义与评论本体不同】评论是软删（is_deleted=1 留档），图片行是物理
 * DELETE（deleteCommentImages / deleteContentCommentImages）——图片行只服务
 * 于展示与审核，评论不可见后继续留 URL 只会制造孤儿数据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CommentImage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 图片唯一 ID（主键）
     */
    private Long imageId;

    /**
     * 评论 ID（关联 tb_content_comment.comment_id）
     */
    // 唯一的关联键：所有查询都按它来，没有反向的"图片查评论"路径。
    private Long commentId;

    /**
     * 图片 URL
     */
    // 客户端上传后拿到 OSS 地址再随评论提交；服务端只校验 http(s):// 前缀
    //（sendComment 第 6 步），不回源验证 URL 是否真实可访问。
    private String imageUrl;

    /**
     * 排序（数值越小越靠前）
     */
    // 服务端按前端传入顺序生成（sendComment 里 sort++ 递增），保证展示顺序
    // 与上传顺序一致，不依赖数据库默认排序。
    private Integer sort;

    /**
     * 创建时间
     */
    // 行级插入时间（sendComment 构造时写入 LocalDateTime.now()），批量插入
    //（insertCommentImagesBatch）不回填 imageId，也没有更新语义。
    private LocalDateTime createTime;

}