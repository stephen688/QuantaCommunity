package com.quanta.demo0.answer.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.answer.dto.AnswerAdminQueryDTO;
import com.quanta.demo0.answer.entity.QuestionAnswer;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QuestionMapper {
    @Select("select * from tb_question_answer where answer_id = #{answerId} and is_deleted = 0 ")
    QuestionAnswer selectById(Long answerId);

    @Update("update tb_question_answer set comment_count = GREATEST(0, comment_count + #{i}) where answer_id = #{answerId}")
    int updateAnswerCommentCount(@Param("answerId") Long answerId, @Param("i") int i);

    @Update("update tb_question_answer set like_count = GREATEST(0, like_count + #{i}) where answer_id = #{answerId}")
    int updateAnswerLikeCount(@Param("answerId") Long answerId, @Param("i") int i);


    void softDeleteAnswers(@Param("questionId") Long questionId);

    void insertAnswer(QuestionAnswer answer);



    List<QuestionAnswer> selectAnswersByQuestionId(Long questionId);


    @Update("UPDATE tb_question_answer SET is_accepted = 0, update_time = NOW() WHERE question_id = #{questionId} AND is_accepted = 1")
    void clearAcceptedAnswer(Long questionId);

    @Update("UPDATE tb_question_answer SET is_accepted = 1, update_time = NOW() WHERE answer_id = #{answerId}")
    int acceptAnswer(Long answerId);


    /**
     * 软删除回答
     *
     * @param answerId 回答 ID
     */
    @Update("UPDATE tb_question_answer SET is_deleted = 1, update_time = NOW() WHERE answer_id = #{answerId} AND is_deleted = 0")
    void softDeleteAnswer(Long answerId);

    @Select("SELECT * FROM tb_question_answer WHERE question_id = #{questionId} AND is_deleted = 0 AND is_accepted = 1")
    QuestionAnswer selectAnswerByQuestionId(Long contentId);

    Page<QuestionAnswer> pageAdmin(@Param("query") AnswerAdminQueryDTO query);

    void updateAnswer(QuestionAnswer updateAnswer);

    /**
     * 分页查询所有有效回答（供向量库全量重建使用）
     * @param offset 偏移量
     * @param limit 每批数量
     * @return 回答列表
     */
    List<QuestionAnswer> selectAllAnswersForReindex(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 分页查询审核通过且未删除的回答，供向量库重建使用。
     *
     * @param offset 偏移量
     * @param limit 每批数量
     * @return 审核通过的回答列表
     */
    List<QuestionAnswer> selectApprovedAnswersForReindex(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 只有待审核回答才能修改为最终状态。
     */
    int updateAnswerAuditStatusIfPending(@Param("answerId") Long answerId,
                                         @Param("auditStatus") Integer auditStatus,
                                         @Param("rejectReason") String rejectReason);

    /**
     * 管理员审核使用旧状态作为并发条件，防止覆盖同时完成的 AI 审核。
     */
    int updateAnswerAuditStatusIfCurrent(@Param("answerId") Long answerId,
                                         @Param("oldAuditStatus") Integer oldAuditStatus,
                                         @Param("newAuditStatus") Integer newAuditStatus,
                                         @Param("rejectReason") String rejectReason);

}
