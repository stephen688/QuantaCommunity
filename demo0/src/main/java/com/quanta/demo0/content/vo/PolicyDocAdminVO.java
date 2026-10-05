package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 政策源文档管理端展示对象。
 *
 * <p>该 VO 仅描述 MySQL 源文档状态和时间，不宣称文档已经写入 Qdrant 或完成 Bot 摄取。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyDocAdminVO {

    private Long id;
    private String docId;
    private String title;
    private String content;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
