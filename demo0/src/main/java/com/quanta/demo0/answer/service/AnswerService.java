package com.quanta.demo0.answer.service;

import com.quanta.demo0.answer.dto.AnswerDTO;
import com.quanta.demo0.answer.vo.AnswerVO;
import com.quanta.demo0.interaction.vo.LikeResultVO;
import org.springframework.stereotype.Service;

import java.util.List;


public interface AnswerService {
    AnswerVO publishAnswer(AnswerDTO answerDTO);

    List<AnswerVO> getAnswersByQuestionId(Long questionId);

    void acceptAnswer(Long answerId);

    LikeResultVO likeAnswer(Long answerId, boolean liked);

    void deleteAnswer(Long answerId);

    AnswerVO getAnswerDetail(Long answerId);
}
