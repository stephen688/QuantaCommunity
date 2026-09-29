package com.quanta.demo0.answer.service;

import com.quanta.demo0.answer.dto.AnswerDTO;
import com.quanta.demo0.answer.vo.AnswerVO;

import java.util.List;

/** 回答发布、采纳和删除等状态变更服务。 */
public interface AnswerCommandService {

    AnswerVO publishAnswer(AnswerDTO answerDTO);

    void acceptAnswer(Long answerId);

    void deleteAnswer(Long answerId);

    /**
     * 软删除问题下当前可见回答，返回原查询结果中的回答 ID。
     *
     * <p>内容域删除问题时通过该端口调用，避免直接依赖回答 Mapper。</p>
     */
    List<Long> deleteByQuestionId(Long questionId);
}
