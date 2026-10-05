package com.quanta.demo0.comment.service;

import com.quanta.demo0.comment.vo.BotCommentChainVO;
import com.quanta.demo0.comment.vo.BotCommentHistoryVO;
import com.quanta.demo0.comment.vo.BotCommentTreeVO;

/** bot 系统账号使用的只读评论接口（C-2）。 */
/*
 * 只走"可见口径"（audit_status = 1 且 is_deleted = 0），输出最小化契约：
 * 只有原始事实（文本/图片/时间/用户 id），不带昵称、点赞等展示数据。
 */
public interface BotCommentService {

    /** 评论链：从指定评论沿祖先（replyCommentId 优先，其次 parentId）回溯到根，输出时间正序对话链。 */
    BotCommentChainVO getChain(Long commentId);

    /** 评论历史：某用户在某帖子下的全部可见评论，时间正序分页。 */
    BotCommentHistoryVO getHistory(Long userId, Long postId, int pageNum, int pageSize);

    /** 评论树：帖子下全部可见评论（一楼 + 回复混排的楼层流），按时间正/倒序分页。 */
    BotCommentTreeVO getTree(Long postId, int pageNum, int pageSize, String sortType);
}
