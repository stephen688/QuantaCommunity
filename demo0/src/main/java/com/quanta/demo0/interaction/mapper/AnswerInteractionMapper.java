package com.quanta.demo0.interaction.mapper;

import com.quanta.demo0.interaction.entity.AnswerLiked;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 回答互动关系持久化接口。
 *
 * <p>回答主体查询仍由 answer/question mapper 负责；这里仅承接点赞关系和互动计数更新，
 * 避免回答主服务直接把互动表 SQL 混在生命周期操作中。</p>
 */
@Mapper
public interface AnswerInteractionMapper {

    int insertAnswerLiked(AnswerLiked answerLiked);

    int deleteAnswerLikedByUser(@Param("answerId") Long answerId, @Param("userId") Long userId);

    int updateAnswerLikeCount(@Param("answerId") Long answerId, @Param("i") int i);

    int updateAnswerCommentCount(@Param("answerId") Long answerId, @Param("i") int i);

    void deleteAnswerLikedByAnswerId(Long answerId);

    void deleteAnswerCommentImages(Long answerId);

    void deleteAnswerCommentLiked(Long answerId);

    void softDeleteAnswerComments(Long answerId);
}
