package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** history 接口响应：用户在某帖下的可见评论分页。 */
/**
 * 【用途】QuantaBot 识别"这个用户在帖子里已经说过什么"，避免重复回答、
 * 支持连续追问。查询口径：user_id + content_id 双条件、只含可见评论
 * （selectBotHistory：audit_status=1 且 is_deleted=0，create_time 正序）。
 * total 为该用户在该帖下的可见评论总数，list 为当前页。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 当前页评论节点（节点形状与 tree/chain 共用 BotCommentNodeVO）。
    private List<BotCommentNodeVO> list;
    private long total;
}
