package com.quanta.demo0.service;

import com.quanta.demo0.comment.vo.BotCommentChainVO;
import com.quanta.demo0.comment.vo.BotCommentHistoryVO;
import com.quanta.demo0.comment.vo.BotCommentTreeVO;

/** bot 系统账号使用的只读评论接口（C-2）。 */
public interface BotCommentService {

    BotCommentChainVO getChain(Long commentId);

    BotCommentHistoryVO getHistory(Long userId, Long postId, int pageNum, int pageSize);

    BotCommentTreeVO getTree(Long postId, int pageNum, int pageSize, String sortType);
}
