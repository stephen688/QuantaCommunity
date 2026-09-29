package com.quanta.demo0.answer.service.impl;

import com.quanta.demo0.answer.entity.QuestionAnswer;
import com.quanta.demo0.answer.service.AnswerQueryService;
import com.quanta.demo0.answer.vo.AnswerRagSnapshotVO;
import com.quanta.demo0.answer.vo.AnswerVO;
import com.quanta.demo0.answer.vo.AnswerSnapshotVO;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.answer.mapper.QuestionMapper;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 回答查询实现。只负责回答读模型组装，不修改回答状态。 */
@Service
public class AnswerQueryServiceImpl implements AnswerQueryService {

    @Autowired
    private ContentQueryService contentQueryService;
    @Autowired
    private UserQueryService userQueryService;
    @Autowired
    private QuestionMapper questionMapper;

    @Override
    public AnswerSnapshotVO getAnswerSnapshot(Long answerId) {
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            return null;
        }
        return AnswerSnapshotVO.builder().answerId(answer.getAnswerId())
                .questionId(answer.getQuestionId()).userId(answer.getUserId())
                .content(answer.getContent()).likeCount(answer.getLikeCount())
                .commentCount(answer.getCommentCount()).isAccepted(answer.getIsAccepted())
                .auditStatus(answer.getAuditStatus()).isDeleted(answer.getIsDeleted())
                .createTime(answer.getCreateTime()).build();
    }

    @Override
    public List<AnswerVO> getAnswersByQuestionId(Long questionId) {
        ContentSnapshotVO question = contentQueryService.getContentSnapshot(questionId);
        if (question == null) {
            throw new ContentFailedException("问题不存在");
        }
        List<QuestionAnswer> answers = questionMapper.selectAnswersByQuestionId(questionId);
        if (answers == null || answers.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> userIds = answers.stream().map(QuestionAnswer::getUserId).collect(Collectors.toSet());
        List<UserAuthInfoVO> userInfoList = userQueryService.getUserAuthInfos(new ArrayList<>(userIds));
        Map<Long, UserAuthInfoVO> userInfoMap = userInfoList.stream()
                .collect(Collectors.toMap(UserAuthInfoVO::getUserId, u -> u));
        return answers.stream().map(answer -> toVO(answer, userInfoMap.get(answer.getUserId()))).collect(Collectors.toList());
    }

    @Override
    public AnswerVO getAnswerDetail(Long answerId) {
        if (answerId == null) {
            throw new ContentFailedException("answerId 不能为空");
        }
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            throw new ContentFailedException("回答不存在");
        }
        if (answer.getIsDeleted() != null && answer.getIsDeleted() == 1) {
            throw new ContentFailedException("回答已被删除");
        }
        return toVO(answer, userQueryService.getUserAuthInfo(answer.getUserId()));
    }

    @Override
    public AnswerRagSnapshotVO getAnswerRagSnapshot(Long answerId) {
        if (answerId == null) {
            return null;
        }
        QuestionAnswer answer = questionMapper.selectById(answerId);
        if (answer == null) {
            return null;
        }
        return toRagSnapshot(answer);
    }

    @Override
    public List<AnswerRagSnapshotVO> getApprovedAnswerRagSnapshots(int offset, int limit) {
        List<QuestionAnswer> answers = questionMapper.selectApprovedAnswersForReindex(offset, limit);
        if (answers == null || answers.isEmpty()) {
            return new ArrayList<>();
        }
        return answers.stream()
                .filter(answer -> answer != null
                        && Integer.valueOf(0).equals(answer.getIsDeleted())
                        && Integer.valueOf(1).equals(answer.getAuditStatus()))
                .map(this::toRagSnapshot)
                .collect(Collectors.toList());
    }

    private AnswerRagSnapshotVO toRagSnapshot(QuestionAnswer answer) {
        ContentSnapshotVO question = contentQueryService.getContentSnapshot(answer.getQuestionId());
        return AnswerRagSnapshotVO.builder()
                .answerId(answer.getAnswerId())
                .questionId(answer.getQuestionId())
                .userId(answer.getUserId())
                .questionTitle(question == null ? "" : question.getTitle())
                .questionContent(question == null ? "" : question.getContent())
                .content(answer.getContent())
                .auditStatus(answer.getAuditStatus())
                .isDeleted(answer.getIsDeleted())
                .createTime(answer.getCreateTime())
                .build();
    }

    private AnswerVO toVO(QuestionAnswer answer, UserAuthInfoVO userInfo) {
        return AnswerVO.builder()
                .answerId(answer.getAnswerId())
                .questionId(answer.getQuestionId())
                .userId(answer.getUserId())
                .nickName(userInfo != null ? userInfo.getNickName() : "未知用户")
                .avatarUrl(userInfo != null ? userInfo.getAvatarUrl() : "")
                .quantaBatch(userInfo != null ? userInfo.getQuantaBatch() : "")
                .content(answer.getContent())
                .likeCount(answer.getLikeCount())
                .commentCount(answer.getCommentCount())
                .isAccepted(answer.getIsAccepted())
                .auditStatus(answer.getAuditStatus())
                .createTime(answer.getCreateTime())
                .build();
    }
}
