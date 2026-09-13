package com.quanta.demo0.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReindexResult {
    private long total;      // MySQL 扫描总数
    private long success;    // ES 写入成功数
    private long failure;     // ES 写入失败数
    private boolean completed;    // 是否完成（true表示已完成，false 表示未完成）
}
