package com.quanta.demo0.vo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 热门问题 VO（搜索发现页）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class HotQuestionVO implements Serializable {

    /**
     * 内容唯一 ID
     */
    private Long contentId;

    /**
     * 问题标题
     */
    private String title;

    /**
     * 点赞数
     */
    private Integer liked;
}