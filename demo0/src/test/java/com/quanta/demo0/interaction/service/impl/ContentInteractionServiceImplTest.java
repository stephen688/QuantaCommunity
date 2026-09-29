package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.interaction.entity.ContentLiked;
import com.quanta.demo0.interaction.mapper.ContentInteractionMapper;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import com.quanta.demo0.platform.security.context.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentInteractionServiceImplTest {

    private static final long USER_ID = 3L;
    private static final long AUTHOR_ID = 9L;
    private static final long CONTENT_ID = 10L;

    @Mock
    private ContentInteractionMapper contentInteractionMapper;
    @Mock
    private ContentCounterService contentCounterService;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    @InjectMocks
    private ContentInteractionServiceImpl service;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void 新增点赞后同步调用内容域更新计数() {
        when(contentCounterService.getContentSnapshot(CONTENT_ID))
                .thenReturn(ContentSnapshotVO.builder()
                        .contentId(CONTENT_ID)
                        .publishUserId(AUTHOR_ID)
                        .likedCount(0)
                        .collectCount(0)
                        .build());
        when(contentInteractionMapper.insertContentLiked(any(ContentLiked.class))).thenReturn(1);
        when(contentCounterService.changeLikedCount(CONTENT_ID, 1)).thenReturn(1);

        service.likeContent(CONTENT_ID, true);

        InOrder inOrder = inOrder(contentInteractionMapper, contentCounterService);
        inOrder.verify(contentInteractionMapper).insertContentLiked(any(ContentLiked.class));
        inOrder.verify(contentCounterService).changeLikedCount(CONTENT_ID, 1);
        verify(outboxEventService).createUserBehaviorEvent(USER_ID, CONTENT_ID, "LIKE");
    }
}
