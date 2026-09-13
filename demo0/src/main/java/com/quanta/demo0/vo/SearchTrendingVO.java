package com.quanta.demo0.vo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 搜索热门发现聚合 VO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchTrendingVO implements Serializable {

    /**
     * 热门关键词列表
     */
    @Builder.Default
    private List<String> hotKeywords = new ArrayList<>();

    /**
     * 热门问题列表
     */
    @Builder.Default
    private List<HotQuestionVO> hotQuestions = new ArrayList<>();

    /**
     * 热门校友列表
     */
    @Builder.Default
    private List<HotAlumniVO> hotAlumni = new ArrayList<>();
}