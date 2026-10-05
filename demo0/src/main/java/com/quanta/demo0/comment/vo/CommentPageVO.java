package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * C 端评论 / 回复分页响应（/comment/list 与 /comment/replyList 共用同一形状）。
 *
 * ============================================================
 * 【为什么 list 是 List&lt;Map&gt; 而不是强类型 VO？】
 * ============================================================
 * 这是历史占位写法（见字段旁原注释），list 元素由 CommentQueryServiceImpl
 * 逐键 put 组装（commentId / content / likeCount / isLiked / isBot /
 * isContentAuthor / isAnswerAuthor / nickName / replyList ...）。
 * **代价已经显现**：字段名靠字符串约定，拼错编译器不报错；两个接口的
 * 元素结构略有差异（一级元素带 replyCount/replyList，回复元素带被回复人信息），
 * 只能靠读组装代码确认——按 content 包 BotContentSyncVO 的契约先例，
 * 理想演进方向是收敛成强类型 CommentListItemVO。
 */
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
    // hasMore 的判定口径：pageNum * pageSize < total（commentPage / replyPage 第 11/4 步），
    // 前端据此决定是否继续翻页，不需要自己算总页数。
    private List<Map<String, Object>> list;
}
