package com.quanta.demo0.answer.service.impl;

import com.quanta.demo0.answer.mapper.QuestionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnswerCounterServiceImplTest {

    @Mock
    private QuestionMapper questionMapper;

    @InjectMocks
    private AnswerCounterServiceImpl answerCounterService;

    @Test
    void updateCommentCountDelegatesToAnswerMapper() {
        answerCounterService.updateCommentCount(7L, 1);

        verify(questionMapper).updateAnswerCommentCount(7L, 1);
    }
}
