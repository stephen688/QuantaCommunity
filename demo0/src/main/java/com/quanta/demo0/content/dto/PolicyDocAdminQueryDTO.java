package com.quanta.demo0.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 政策知识库管理端分页查询入参。
 *
 * <p>查询只读取源文档表；status 为空表示全部，ACTIVE/DELETED 分别表示未软删和墓碑文档。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyDocAdminQueryDTO {

    /** 页码，服务层会收紧到至少 1。 */
    @Builder.Default
    private Integer pageNum = 1;

    /** 每页大小，服务层会收紧到 1..100。 */
    @Builder.Default
    private Integer pageSize = 10;

    /** 按 docId、标题或正文模糊检索。 */
    private String keyword;

    /** ACTIVE、DELETED 或空值（全部）。 */
    private String status;
}
