package com.quanta.demo0.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ScrollResult {
    private List<?> list;
    private Double minScore;
    private Integer offset;
    /**
     * 是否有更多数据
     */
    private Boolean hasMore;
}


