package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageVO<T> implements Serializable {
    //TODO:把这个和那个pageResultVO合并,后续
    private List<T> list;
    private Long total;
    private Integer totalPage;
    private Integer pageNum;
    private Integer pageSize;
    private Boolean hasMore;
}
