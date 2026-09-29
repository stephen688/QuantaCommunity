package com.quanta.demo0.comment.service.impl;

import com.quanta.demo0.comment.service.CommentCounterService;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 评论计数服务实现，统一通过领域 Mapper 执行同步数据库更新。 */
@Service
@RequiredArgsConstructor
public class CommentCounterServiceImpl implements CommentCounterService {

    private final CommentMapper commentMapper;
    private final QuestionMapper questionMapper;

    /** 调整内容评论总数并返回受影响行数。 */
    @Override
    public int changeCommentCount(Long contentId, int delta) {
        return commentMapper.updateCommentCount(contentId, delta);
    }

    /** 调整回答评论总数并返回受影响行数。 */
    @Override
    public int changeAnswerCommentCount(Long answerId, int delta) {
        return questionMapper.updateAnswerCommentCount(answerId, delta);
    }

    /** 调整评论点赞总数并返回受影响行数。 */
    @Override
    public int changeCommentLikeCount(Long commentId, int delta) {
        return commentMapper.updateLikeCount(commentId, delta);
    }
}
