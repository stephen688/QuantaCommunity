package com.quanta.demo0.service.Impl;

import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentCollect;
import com.quanta.demo0.entity.ContentLiked;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.service.OutboxEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * likeContent / collect 的画像行为事件挂载测试（推荐流个性化 D2/D9）。
 * 断言：只有真正新增的正向行为才创建用户行为 Outbox 事件；
 * 事件创建与业务写发生在同一方法内（同层即同事务，与 HotScore 事件挂法一致）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentServiceImplBehaviorEventTest {

    private static final Long USER_ID = 3L;
    private static final Long AUTHOR_ID = 999L;
    private static final Long CONTENT_ID = 10L;

    @Mock
    private ContentMapper contentMapper;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    @InjectMocks
    private ContentServiceImpl service;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    private Content content(Long publishUserId) {
        return Content.builder()
                .contentId(CONTENT_ID)
                .publishUserId(publishUserId)
                .liked(0)
                .collectCount(0)
                .build();
    }

    @Test
    void 新增点赞_发LIKE行为事件_且在业务写之后() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(AUTHOR_ID));
        when(contentMapper.insertContentLiked(any(ContentLiked.class))).thenReturn(1);
        when(contentMapper.updateLiked(CONTENT_ID, 1)).thenReturn(true);

        service.likeContent(CONTENT_ID, true);

        verify(outboxEventService).createUserBehaviorEvent(USER_ID, CONTENT_ID, "LIKE");
        // 事件创建必须发生在点赞明细写入之后、同一方法内——与 HotScore 事件同层，即同一事务。
        InOrder inOrder = inOrder(contentMapper, outboxEventService);
        inOrder.verify(contentMapper).insertContentLiked(any(ContentLiked.class));
        inOrder.verify(outboxEventService).createUserBehaviorEvent(USER_ID, CONTENT_ID, "LIKE");
    }

    @Test
    void 取消点赞_不发行为事件_D9取消不回滚() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(AUTHOR_ID));
        when(contentMapper.deleteContentLikedByUser(CONTENT_ID, USER_ID)).thenReturn(1);
        when(contentMapper.updateLiked(CONTENT_ID, -1)).thenReturn(true);

        service.likeContent(CONTENT_ID, false);

        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 重复点赞未真正新增_不发行为事件() {
        // 明细已存在时插入返回 0，本次没有真正新增点赞
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(AUTHOR_ID));
        when(contentMapper.insertContentLiked(any(ContentLiked.class))).thenReturn(0);

        service.likeContent(CONTENT_ID, true);

        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 自赞_不发行为事件_跟随通知事件条件语义() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(USER_ID));
        when(contentMapper.insertContentLiked(any(ContentLiked.class))).thenReturn(1);
        when(contentMapper.updateLiked(CONTENT_ID, 1)).thenReturn(true);

        service.likeContent(CONTENT_ID, true);

        // 自赞不是对他人内容的兴趣信号，行为事件与通知事件保持同一判定条件
        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 新增收藏_发COLLECT行为事件_且在业务写之后() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(AUTHOR_ID));
        when(contentMapper.insertCollect(any(ContentCollect.class))).thenReturn(1);
        when(contentMapper.updateCollectCount(CONTENT_ID, 1)).thenReturn(1);

        service.collect(CONTENT_ID, true);

        verify(outboxEventService).createUserBehaviorEvent(USER_ID, CONTENT_ID, "COLLECT");
        // 事件创建必须发生在收藏明细写入之后、同一方法内——与 HotScore 事件同层，即同一事务。
        InOrder inOrder = inOrder(contentMapper, outboxEventService);
        inOrder.verify(contentMapper).insertCollect(any(ContentCollect.class));
        inOrder.verify(outboxEventService).createUserBehaviorEvent(USER_ID, CONTENT_ID, "COLLECT");
    }

    @Test
    void 取消收藏_不发行为事件_D9取消不回滚() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(AUTHOR_ID));
        when(contentMapper.deleteCollect(CONTENT_ID, USER_ID)).thenReturn(1);
        when(contentMapper.updateCollectCount(CONTENT_ID, -1)).thenReturn(1);

        service.collect(CONTENT_ID, false);

        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 重复收藏未真正新增_不发行为事件() {
        // 明细已存在时插入返回 0，本次没有真正新增收藏
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content(AUTHOR_ID));
        when(contentMapper.insertCollect(any(ContentCollect.class))).thenReturn(0);

        service.collect(CONTENT_ID, true);

        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }
}
