package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FollowFeedQueryDTO {
    /**
     * 上一次查询的最小时间戳
     * 首次请求时为 null
     */

    private Long lastId;

    /**
     * 偏移量
     * 首次请求时为 0，后续请求根据上一次返回的内容数量递增
     */
    @Builder.Default
    private Integer offset = 0;

    /**
     * 内容类型（可选）：1-生活求助 2-专业问答 不传则返回所有类型的内容
     */
    private Integer contentType;

    /**
     * 每页数量
     */
    @Builder.Default
    private Integer pageSize = 5;
}
