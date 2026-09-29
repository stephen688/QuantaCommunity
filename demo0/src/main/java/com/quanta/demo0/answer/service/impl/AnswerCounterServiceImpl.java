package com.quanta.demo0.answer.service.impl;

import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.answer.mapper.QuestionMapper;
import com.quanta.demo0.answer.service.AnswerCounterService;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/** 回答点赞/评论计数的同步写入实现。 */
@Service
public class AnswerCounterServiceImpl implements AnswerCounterService {

    @Autowired
    private QuestionMapper questionMapper;

    @Override
    public int updateLikeCount(Long answerId, int delta) {
        return questionMapper.updateAnswerLikeCount(answerId, delta);
    }

    @Override
    public int updateCommentCount(Long answerId, int delta) {
        return questionMapper.updateAnswerCommentCount(answerId, delta);
    }

    @Override
    public AnswerSnapshotVO getAnswerSnapshot(Long answerId) {
        return toSnapshot(questionMapper.selectById(answerId));
    }

    @Override
    public List<AnswerSnapshotVO> getAnswerSnapshotsByQuestionId(Long questionId) {
        List<QuestionAnswer> answers = questionMapper.selectAnswersByQuestionId(questionId);
        if (answers == null || answers.isEmpty()) {
            return Collections.emptyList();
        }
        return answers.stream().map(this::toSnapshot).toList();
    }

    private AnswerSnapshotVO toSnapshot(QuestionAnswer answer) {
        if (answer == null) {
            return null;
        }
        return AnswerSnapshotVO.builder()
                .answerId(answer.getAnswerId())
                .questionId(answer.getQuestionId())
                .userId(answer.getUserId())
                .content(answer.getContent())
                .likeCount(answer.getLikeCount())
                .commentCount(answer.getCommentCount())
                .isAccepted(answer.getIsAccepted())
                .auditStatus(answer.getAuditStatus())
                .isDeleted(answer.getIsDeleted())
                .createTime(answer.getCreateTime())
                .build();
    }
}
