package com.quanta.demo0.answer.service;

import com.quanta.demo0.answer.vo.AnswerRagSnapshotVO;
import com.quanta.demo0.answer.vo.AnswerVO;

import java.util.List;

/** 回答列表和详情查询服务。 */
public interface AnswerQueryService {

    List<AnswerVO> getAnswersByQuestionId(Long questionId);

    AnswerVO getAnswerDetail(Long answerId);

    /**
     * 查询回答向量同步所需的稳定快照。
     *
     * @param answerId 回答 ID
     * @return 快照；回答不存在或已被删除时返回 {@code null}
     */
    AnswerRagSnapshotVO getAnswerRagSnapshot(Long answerId);

    /**
     * 分页查询已审核回答的 RAG 快照。
     *
     * @param offset 偏移量
     * @param limit  批次大小
     * @return 已审核且未删除的回答快照
     */
    List<AnswerRagSnapshotVO> getApprovedAnswerRagSnapshots(int offset, int limit);
}
