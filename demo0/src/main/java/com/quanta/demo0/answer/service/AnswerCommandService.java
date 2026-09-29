package com.quanta.demo0.answer.service;

import com.quanta.demo0.answer.dto.AnswerDTO;
import com.quanta.demo0.answer.vo.AnswerVO;

/** 回答发布、采纳和删除等状态变更服务。 */
public interface AnswerCommandService {

    AnswerVO publishAnswer(AnswerDTO answerDTO);

    void acceptAnswer(Long answerId);

    void deleteAnswer(Long answerId);
}
