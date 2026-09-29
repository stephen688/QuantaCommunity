package com.quanta.demo0.answer.service;

/** 回答互动计数同步服务。计数更新与当前业务事务同步完成，不发布计数事件。 */
public interface AnswerCounterService {

    int updateLikeCount(Long answerId, int delta);

    int updateCommentCount(Long answerId, int delta);
}
