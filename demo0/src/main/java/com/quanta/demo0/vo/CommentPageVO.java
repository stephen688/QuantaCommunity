package com.quanta.demo0.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentPageVO implements Serializable {
    private Integer pageNum;//当前页码
    private Integer pageSize;//每页记录数
    private Long total;//总记录数
    private Boolean hasMore;//是否有更多数据
    // 先用 Map 占位，后续你再替换成 CommentListItemVO
    private List<Map<String, Object>> list;
}
