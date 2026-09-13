package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchDTO implements Serializable {
    /**
     * 搜索关键词（必填）
     */
    private String keyword;

    /**
     * 内容类型（可选）：1-生活求助 2-专业问答 null-全部
     */
    private Integer contentType;

    /**
     * 排序方式（可选）：new-按时间 hot-按热度 默认 new
     */
    @Builder.Default
    private String sortType = "new";

    /**
     * 当前页码（默认 1）
     */
    @Builder.Default
    private Integer current = 1;

    /**
     * 每页条数（默认 10）
     */
    @Builder.Default
    private Integer pageSize = 10;
}