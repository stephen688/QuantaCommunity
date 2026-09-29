package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.content.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.interaction.entity.ContentLiked;
import com.quanta.demo0.interaction.entity.ContentCollect;
import com.quanta.demo0.interaction.mapper.ContentInteractionMapper;
import com.quanta.demo0.feed.mq.producer.FeedEventProducer;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.search.mq.producer.SearchEventProducer;
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
import static org.mockito.Mockito.never;
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
    private FeedEventProducer feedEventProducer;
    @Mock
    private NotificationEventProducer notificationEventProducer;
    @Mock
    private SearchEventProducer searchEventProducer;
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
        when(contentCounterService.getContentSnapshot(CONTENT_ID)).thenReturn(snapshot(AUTHOR_ID));
        when(contentInteractionMapper.insertContentLiked(any(ContentLiked.class))).thenReturn(1);
        when(contentCounterService.changeLikedCount(CONTENT_ID, 1)).thenReturn(1);

        service.likeContent(CONTENT_ID, true);

        InOrder inOrder = inOrder(contentInteractionMapper, contentCounterService);
        inOrder.verify(contentInteractionMapper).insertContentLiked(any(ContentLiked.class));
        inOrder.verify(contentCounterService).changeLikedCount(CONTENT_ID, 1);
        verify(feedEventProducer).createUserBehaviorEvent(USER_ID, CONTENT_ID, "LIKE");
    }

    @Test
    void 取消点赞不发画像行为事件() {
        when(contentCounterService.getContentSnapshot(CONTENT_ID)).thenReturn(snapshot(AUTHOR_ID));
        when(contentInteractionMapper.deleteContentLikedByUser(CONTENT_ID, USER_ID)).thenReturn(1);
        when(contentCounterService.changeLikedCount(CONTENT_ID, -1)).thenReturn(1);

        service.likeContent(CONTENT_ID, false);

        verify(contentCounterService).changeLikedCount(CONTENT_ID, -1);
        verify(feedEventProducer, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 重复点赞未新增关系时不更新计数也不发画像事件() {
        when(contentCounterService.getContentSnapshot(CONTENT_ID)).thenReturn(snapshot(AUTHOR_ID));
        when(contentInteractionMapper.insertContentLiked(any(ContentLiked.class))).thenReturn(0);

        service.likeContent(CONTENT_ID, true);

        verify(contentCounterService, never()).changeLikedCount(any(), any(Integer.class));
        verify(feedEventProducer, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 自赞不发画像行为事件() {
        when(contentCounterService.getContentSnapshot(CONTENT_ID)).thenReturn(snapshot(USER_ID));
        when(contentInteractionMapper.insertContentLiked(any(ContentLiked.class))).thenReturn(1);
        when(contentCounterService.changeLikedCount(CONTENT_ID, 1)).thenReturn(1);

        service.likeContent(CONTENT_ID, true);

        verify(feedEventProducer, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 新增收藏后同步更新计数并发送画像事件() {
        when(contentCounterService.getContentSnapshot(CONTENT_ID)).thenReturn(snapshot(AUTHOR_ID));
        when(contentInteractionMapper.insertCollect(any(ContentCollect.class))).thenReturn(1);
        when(contentCounterService.changeCollectCount(CONTENT_ID, 1)).thenReturn(1);

        service.collect(CONTENT_ID, true);

        InOrder inOrder = inOrder(contentInteractionMapper, contentCounterService);
        inOrder.verify(contentInteractionMapper).insertCollect(any(ContentCollect.class));
        inOrder.verify(contentCounterService).changeCollectCount(CONTENT_ID, 1);
        verify(feedEventProducer).createUserBehaviorEvent(USER_ID, CONTENT_ID, "COLLECT");
    }

    @Test
    void 取消收藏不发画像行为事件() {
        when(contentCounterService.getContentSnapshot(CONTENT_ID)).thenReturn(snapshot(AUTHOR_ID));
        when(contentInteractionMapper.deleteCollect(CONTENT_ID, USER_ID)).thenReturn(1);
        when(contentCounterService.changeCollectCount(CONTENT_ID, -1)).thenReturn(1);

        service.collect(CONTENT_ID, false);

        verify(feedEventProducer, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 重复收藏未新增关系时不更新计数也不发画像事件() {
        when(contentCounterService.getContentSnapshot(CONTENT_ID)).thenReturn(snapshot(AUTHOR_ID));
        when(contentInteractionMapper.insertCollect(any(ContentCollect.class))).thenReturn(0);

        service.collect(CONTENT_ID, true);

        verify(contentCounterService, never()).changeCollectCount(any(), any(Integer.class));
        verify(feedEventProducer, never()).createUserBehaviorEvent(any(), any(), any());
    }

    private ContentSnapshotVO snapshot(long publishUserId) {
        return ContentSnapshotVO.builder()
                .contentId(CONTENT_ID)
                .publishUserId(publishUserId)
                .likedCount(0)
                .collectCount(0)
                .build();
    }
}
