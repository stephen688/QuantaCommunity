package com.quanta.demo0.answer.service.impl;

import com.quanta.demo0.answer.service.AnswerCounterService;
import com.quanta.demo0.interaction.mapper.AnswerInteractionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 回答点赞/评论计数的同步写入实现。 */
@Service
public class AnswerCounterServiceImpl implements AnswerCounterService {

    @Autowired
    private AnswerInteractionMapper answerInteractionMapper;

    @Override
    public int updateLikeCount(Long answerId, int delta) {
        return answerInteractionMapper.updateAnswerLikeCount(answerId, delta);
    }

    @Override
    public int updateCommentCount(Long answerId, int delta) {
        return answerInteractionMapper.updateAnswerCommentCount(answerId, delta);
    }
}
