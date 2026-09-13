package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CollectResultVO implements Serializable {

    /**
     * 是否已收藏
     */
    private Boolean isCollect;

    /**
     * 收藏总数（可选，如果需要统计）
     */
    private Integer collectCount;
}