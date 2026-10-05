package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** tree 接口响应：帖子全部可见楼层分页。 */
/**
 * 【"楼层"的口径】list 是帖下全部可见评论（audit_status=1 且 is_deleted=0）
 * 平铺分页，一级评论与楼内回复混排、不嵌套，服务端按时间排序给平表，
 * 对端靠 parentId/replyCommentId 字段自行还原结构（见 BotCommentNodeVO）。
 * total 是全量可见楼层数（countBotFloors），不是当前页条数；
 * 分页参数已在服务端归一化（pageNum>=1，1<=pageSize<=200）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentTreeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 全量可见楼层总数，供对端计算剩余页数。
    private long total;
    // 当前页楼层，asc/desc 由请求 sortType 决定（selectBotFloorsAsc/Desc 两分支）。
    private List<BotCommentNodeVO> list;
}
