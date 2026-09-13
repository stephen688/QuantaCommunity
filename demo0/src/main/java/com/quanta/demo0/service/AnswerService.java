package com.quanta.demo0.service;

import com.quanta.demo0.dto.AnswerDTO;
import com.quanta.demo0.vo.AnswerVO;
import com.quanta.demo0.vo.LikeResultVO;
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
