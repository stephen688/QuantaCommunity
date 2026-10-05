package com.quanta.demo0.content.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容图片实体类
 *
 * 映射 tb_content_image 表：一条帖子 0..N 张图。发布时在同一个事务里经
 * ContentMapper.batchInsertImages 批量写入；帖子详情快照（ContentDetailDataLoader）
 * 按 content_id 把它们聚成详情页的图片列表。
 *
 * ============================================================
 * 【为什么单独一张表，而不是在 tb_content 里存 JSON 数组？】
 * ============================================================
 * 对比两种写法：
 *   - JSON 列：省一次关联，但没法按图片维度做 SQL（至少"按 sort 排序回显"就做不到），
 *     且列表页 select * 会把大段 URL 一起拖出来，白白消耗 IO；
 *   - 独立子表：发布一次批量 insert，详情一次按 content_id 查回，列表页完全不碰它。
 * 结论：**1:N 且只在详情维度使用的附属数据，拆表让主表保持"瘦"**。
 * 代价是删帖时要多执行一次清理（deleteContentImages 物理删除）。
 *
 * 【这张表没有 is_deleted / update_time】
 * 图片行没有独立业务生命，完全从属于主帖：主帖软删时图片直接物理 DELETE，
 * 不需要给下游留墓碑 —— 图片 URL 从不作为独立文档参与任何同步/索引。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentImage implements Serializable {

    /**
     * 图片唯一 ID（主键）
     */
    private Long imageId;

    /**
     * 所属内容 ID，关联 tb_content.content_id
     *
     * 与主帖生命周期绑定：管理端删帖先 deleteContentImages 再 softDeleteContent
     * （见 AdminContentServiceImpl）。
     */
    private Long contentId;

    /**
     * 图片完整 URL（已优化长度，兼容带签名的 OSS/CDN）
     *
     * MQ 链路对单条 URL 另有 4096 字节校验（见 ContentEventProducer 的
     * MAX_IMAGE_URL_BYTES）：大内容走引用（URL/contentId）不走值，消息才不会撑爆 Outbox。
     */
    private String imageUrl;

    /**
     * 图片排序，控制前端展示顺序（0 为第一张）
     */
    private Integer sort;

    /**
     * 图片上传时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

}
