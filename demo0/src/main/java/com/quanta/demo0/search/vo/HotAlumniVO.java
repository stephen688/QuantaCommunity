package com.quanta.demo0.search.vo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 热门校友 VO（搜索发现页）
 *
 * <p>榜单卡片的最小字段集：ID + 昵称 + 头像，与 HotQuestionVO 同一设计思路——
 * 榜单只负责"引流"，详细资料点进用户主页再看，VO 刻意不透出部门/批次等字段，
 * 既缩小序列化体积，也减少发现页的个人资料暴露面。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class HotAlumniVO implements Serializable {

    /**
     * 用户 ID
     *
     * <p>前端拿它跳转用户主页。</p>
     */
    private Long userId;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户头像
     */
    private String avatarUrl;
}