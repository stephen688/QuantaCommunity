package com.quanta.demo0.answer.service;

import com.quanta.demo0.answer.vo.AnswerSnapshotVO;

import java.util.List;

/** 回答互动计数同步服务。计数更新与当前业务事务同步完成，不发布计数事件。 */
public interface AnswerCounterService {

    int updateLikeCount(Long answerId, int delta);

    int updateCommentCount(Long answerId, int delta);

    /** 读取回答事实快照，供互动域做状态校验和通知组装。 */
    AnswerSnapshotVO getAnswerSnapshot(Long answerId);

    /** 查询指定问题下的回答事实快照，供内容级联操作保留事件 ID。 */
    List<AnswerSnapshotVO> getAnswerSnapshotsByQuestionId(Long questionId);
}
