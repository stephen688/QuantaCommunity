package com.quanta.demo0.rag.generation;

import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.rag.model.RagCandidate;
import com.quanta.demo0.rag.model.RagSearchRequest;
import com.quanta.demo0.rag.properties.RagProperties;
import com.quanta.demo0.rag.retrieval.RagRetrieveFacade;
import com.quanta.demo0.user.service.UserQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** RAG 图片装配契约：批量取事实图片，保持候选顺序和原始 URL。 */
class RagSearchServiceImagesTest {
    @Test
    void batchesImagesWithoutChangingCandidateOrderOrRawUrls() {
        ContentQueryService contentQuery = mock(ContentQueryService.class);
        UserQueryService userQuery = mock(UserQueryService.class);
        RagRetrieveFacade retrieve = mock(RagRetrieveFacade.class);
        RagSearchService service = new RagSearchService();
        ReflectionTestUtils.setField(service, "ragProperties", new RagProperties());
        ReflectionTestUtils.setField(service, "contentQueryService", contentQuery);
        ReflectionTestUtils.setField(service, "userQueryService", userQuery);
        ReflectionTestUtils.setField(service, "ragRetrieveFacade", retrieve);
        when(retrieve.retrieveAndFuse("图片", null)).thenReturn(List.of(
                RagCandidate.builder().contentId(8L).build(),
                RagCandidate.builder().contentId(5L).build(),
                RagCandidate.builder().contentId(8L).build()));
        when(contentQuery.getContentFactSnapshots(List.of(8L, 5L))).thenReturn(List.of(
                ContentSnapshotVO.builder().contentId(5L).publishUserId(2L).build(),
                ContentSnapshotVO.builder().contentId(8L).publishUserId(2L).build()));
        when(userQuery.getUserAuthInfos(List.of(2L))).thenReturn(List.of());
        when(contentQuery.getContentFactImageUrlsBatch(List.of(8L, 5L)))
                .thenReturn(Map.of(8L, Arrays.asList("image-8", "", null, "image-8")));

        var response = service.searchWithAi(RagSearchRequest.builder()
                .query("图片").enableAi(false).build());

        assertThat(response.getList()).extracting(ContentVO::getContentId)
                .containsExactly(8L, 5L, 8L);
        assertThat(response.getList().get(0).getImages()).containsExactly("image-8", "", null, "image-8");
        assertThat(response.getList().get(1).getImages()).isEmpty();
        assertThat(response.getList().get(2).getImages()).containsExactly("image-8", "", null, "image-8");
        assertThat(response.getTotal()).isEqualTo(3L);
        assertThat(response.getAiAnswer()).isNull();
        verify(contentQuery).getContentFactImageUrlsBatch(List.of(8L, 5L));
        verify(contentQuery, never()).getContentFactImageUrls(anyLong());
    }
}
