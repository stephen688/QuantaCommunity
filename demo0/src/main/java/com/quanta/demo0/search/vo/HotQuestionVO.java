package com.quanta.demo0.search.vo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 热门问题 VO（搜索发现页）
 *
 * <p>榜单卡片的最小字段集：只带跳转主键、标题和热度值。正文、作者、图片等
 * 一概不进榜单 VO——用户点开详情再走 content 域的 getContentDetail（多级缓存），
 * 榜单本身只负责"引流"，这样榜单缓存可以被整体序列化进 Redis 而体积很小。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class HotQuestionVO implements Serializable {

    /**
     * 内容唯一 ID
     *
     * <p>对应 tb_content 主键，前端拿它跳转问题详情页。</p>
     */
    private Long contentId;

    /**
     * 问题标题
     */
    private String title;

    /**
     * 点赞数
     *
     * <p>榜单排序依据（点赞榜），也是卡片上的热度展示值。</p>
     */
    private Integer liked;
}