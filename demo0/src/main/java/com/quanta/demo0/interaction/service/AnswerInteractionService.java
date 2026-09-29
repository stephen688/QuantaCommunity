package com.quanta.demo0.interaction.service;

import com.quanta.demo0.interaction.vo.LikeResultVO;

/** 回答点赞等互动服务。 */
public interface AnswerInteractionService {

    LikeResultVO likeAnswer(Long answerId, boolean targetLiked);

    /** 删除回答点赞及其评论关联数据，供回答命令/管理服务复用。 */
    void deleteByAnswerId(Long answerId);
}
