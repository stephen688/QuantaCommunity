package com.quanta.demo0.interaction.service;

import com.quanta.demo0.interaction.vo.LikeResultVO;

/** 回答点赞等互动服务。 */
public interface AnswerInteractionService {

    LikeResultVO likeAnswer(Long answerId, boolean targetLiked);
}
