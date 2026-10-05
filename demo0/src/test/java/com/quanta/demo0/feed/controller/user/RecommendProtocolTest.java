package com.quanta.demo0.feed.controller.user;

import com.quanta.demo0.content.controller.user.ContentController;
import com.quanta.demo0.feed.service.FeedQueryService;
import com.quanta.demo0.feed.vo.RecommendPageVO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.exception.RecommendSessionExpiredException;
import com.quanta.demo0.platform.security.context.BaseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 推荐HTTP增量契约：真实控制器必须转发新轮次，不丢回旧分页协议。 */
class RecommendProtocolTest {
    @AfterEach
    void clearIdentity() { BaseContext.removeCurrentId(); }
    @Test
    void newRecommendationRequestKeepsItsSession() throws Exception {
        FeedQueryService feed = mock(FeedQueryService.class);
        when(feed.recommend(any(), any())).thenReturn(RecommendPageVO.builder().list(List.of()).hasMore(false)
                .feedSessionId("7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0").build());
        ContentController controller = new ContentController();
        ReflectionTestUtils.setField(controller, "feedQueryService", feed);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/content/recommend")
                        .param("scene", "recommend")
                        .param("feedSessionId", "7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0")
                        .header("X-Guest-Id", "9845e25a-4e42-4f71-ab21-964ec8a68fb5"))
                .andExpect(jsonPath("$.data.feedSessionId").value("7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0"));
        verify(feed).recommend(any(), eq(RecommendVisitor.from(null, "9845e25a-4e42-4f71-ab21-964ec8a68fb5")));
    }

    @Test
    void authenticatedIdentityWinsAndExpiredSessionUsesHttp409() throws Exception {
        FeedQueryService feed = mock(FeedQueryService.class);
        when(feed.recommend(any(), any())).thenThrow(new RecommendSessionExpiredException());
        ContentController controller = new ContentController();
        ReflectionTestUtils.setField(controller, "feedQueryService", feed);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new RecommendProtocolAdvice()).build();
        BaseContext.setCurrentId(42L);
        mvc.perform(get("/content/recommend").param("scene", "recommend")
                        .param("feedSessionId", "7fac5fa8-8b33-48ec-bd5b-6c2e8e762fc0")
                        .header("X-Guest-Id", "invalid-header"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        verify(feed).recommend(any(), eq(RecommendVisitor.from(42L, null)));
    }
}
