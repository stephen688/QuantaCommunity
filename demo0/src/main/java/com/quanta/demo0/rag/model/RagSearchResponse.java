package com.quanta.demo0.rag.model;

import com.quanta.demo0.vo.ContentVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * RAG 搜索响应 VO
 * 作用：前端最终统一返回结构，包含 AI 总结 + 帖子列表
 *
 * 返回结构：
 *   {
 *     "aiAnswer": {
 *       "enabled": true,
 *       "content": "根据检索结果，关于XX问题有以下建议...",
 *       "reason": null
 *     },
 *     "list": [ ContentVO, ContentVO, ... ],
 *     "total": 50,
 *     "hasMore": true
 *   }
 *
 * 字段说明：
 *   aiAnswer - AI 总结结果，可能为空（AI 失败或未启用时）
 *   list     - 帖子列表，按融合得分排序后的 Top10
 *   total    - 符合条件的帖子总数（用于前端判断是否显示"加载更多"）
 *   hasMore  - 是否还有更多数据（用于滚动分页）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RagSearchResponse implements Serializable {

    /**
     * AI 总结结果
     * 可能为 null（当 rag.aiEnabled=false 时）
     * 或 enabled=false（当 AI 调用失败时）
     */
    private RagAnswer aiAnswer;

    /**
     * 帖子列表
     * 经过双路检索 + 融合排序后的结果
     * 每个元素是 ContentVO，包含帖子详情和用户信息
     */
    private List<ContentVO> list;

    /**
     * 符合条件的帖子总数
     * 用于前端分页展示
     */
    private Long total;

    /**
     * 一直为 false，因为目前没有实现分页加载更多的功能
     * 但保留该字段以便未来扩展
     */
    private boolean hasMore;
}