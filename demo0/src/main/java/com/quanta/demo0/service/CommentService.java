package com.quanta.demo0.service;

import com.quanta.demo0.dto.CommentAddDTO;
import com.quanta.demo0.dto.CommentPageDTO;
import com.quanta.demo0.dto.CommentReportDTO;
import com.quanta.demo0.dto.ReplyPageDTO;
import com.quanta.demo0.vo.CommentPageVO;
import com.quanta.demo0.vo.LikeResultVO;

public interface CommentService {
    Long sendComment(CommentAddDTO commentAddDTO);

    CommentPageVO commentPage(CommentPageDTO commentPageDTO);

    void deleteComment(Long commentId);

    LikeResultVO likeComment(Long commentId, boolean liked);

    CommentPageVO replyPage(ReplyPageDTO replyPageDTO);

    void reportComment(CommentReportDTO commentReportDTO);
}
