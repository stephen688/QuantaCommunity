package com.quanta.demo0.search.service.impl;

import com.github.pagehelper.Page;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.es.mapper.ContentDocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索内容索引服务契约测试。
 *
 * <p>边界：验证 MySQL 内容快照只转换为搜索域文档并交给 ES 文档 Mapper，
 * 服务本身不再直接依赖 Elasticsearch Client。</p>
 */
@ExtendWith(MockitoExtension.class)
class ContentIndexServiceImplTest {

    @Mock
    private ContentQueryService contentQueryService;

    @Mock
    private ContentDocumentMapper contentDocumentMapper;

    @Test
    void visibleContentIsConvertedToSearchOwnedDocument() throws Exception {
        ContentSnapshotVO content = ContentSnapshotVO.builder()
                .contentId(42L)
                .contentType(2)
                .title("索引标题")
                .content("索引正文")
                .publishUserId(7L)
                .auditStatus(1)
                .likedCount(3)
                .collectCount(4)
                .commentCount(5)
                .createTime(LocalDateTime.of(2026, 9, 29, 10, 30))
                .isDeleted(0)
                .build();
        when(contentQueryService.getContentSnapshot(42L)).thenReturn(content);

        ContentIndexServiceImpl service = new ContentIndexServiceImpl(contentQueryService, contentDocumentMapper);
        service.upsertByContentId(42L);

        ArgumentCaptor<ContentDocument> captor = ArgumentCaptor.forClass(ContentDocument.class);
        verify(contentDocumentMapper).index(captor.capture());
        assertThat(captor.getValue().getContentId()).isEqualTo(42L);
        assertThat(captor.getValue().getTitle()).isEqualTo("索引标题");
        assertThat(captor.getValue().getLiked()).isEqualTo(3);
    }
}
