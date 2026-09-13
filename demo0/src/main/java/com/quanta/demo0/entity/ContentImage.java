package com.quanta.demo0.entity;

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
     */
    private Long contentId;

    /**
     * 图片完整 URL（已优化长度，兼容带签名的 OSS/CDN）
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
