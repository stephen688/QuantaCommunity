package com.quanta.demo0.comment.service.impl;

import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentCounterServiceImplTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private QuestionMapper questionMapper;

    @InjectMocks
    private CommentCounterServiceImpl service;

    @Test
    void changeCommentCount_returnsUpdatedRows() {
        when(commentMapper.updateCommentCount(10L, 1)).thenReturn(1);

        assertThat(service.changeCommentCount(10L, 1)).isEqualTo(1);
    }

    @Test
    void changeCommentLikeCount_returnsUpdatedRows() {
        when(commentMapper.updateLikeCount(11L, 1)).thenReturn(1);

        assertThat(service.changeCommentLikeCount(11L, 1)).isEqualTo(1);
    }
}
