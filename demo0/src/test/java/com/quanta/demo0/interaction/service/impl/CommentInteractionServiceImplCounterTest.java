package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.comment.service.CommentCounterService;
import com.quanta.demo0.comment.vo.CommentSnapshotVO;
import com.quanta.demo0.interaction.mapper.CommentInteractionMapper;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.platform.security.context.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 评论互动计数必须经过评论计数服务，保持互动与持久化边界清晰。 */
@ExtendWith(MockitoExtension.class)
class CommentInteractionServiceImplCounterTest {

    @Mock
    private CommentInteractionMapper commentInteractionMapper;

    @Mock
    private CommentCounterService commentCounterService;

    @Mock
    private NotificationEventProducer notificationEventProducer;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private CommentInteractionServiceImpl service;

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void likeComment_updatesLikeCountThroughCounterService() {
        BaseContext.setCurrentId(3L);
        CommentSnapshotVO comment = CommentSnapshotVO.builder()
                .commentId(10L)
                .contentId(20L)
                .userId(99L)
                .likeCount(0)
                .build();
        when(commentCounterService.getCommentSnapshot(10L)).thenReturn(comment);
        when(commentInteractionMapper.insertCommentLikes(10L, 3L)).thenReturn(1);
        when(commentCounterService.changeCommentLikeCount(10L, 1)).thenReturn(1);

        service.likeComment(10L, true);

        verify(commentCounterService).changeCommentLikeCount(10L, 1);
    }
}
