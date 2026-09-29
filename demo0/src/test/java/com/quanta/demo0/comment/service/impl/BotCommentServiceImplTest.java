package com.quanta.demo0.comment.service.impl;

import com.quanta.demo0.comment.vo.BotCommentChainVO;
import com.quanta.demo0.comment.vo.BotCommentHistoryVO;
import com.quanta.demo0.comment.vo.BotCommentTreeVO;
import com.quanta.demo0.comment.entity.CommentImage;
import com.quanta.demo0.content.vo.ContentSnapshotVO;
import com.quanta.demo0.comment.entity.ContentComment;
import com.quanta.demo0.comment.exception.CommentFailedException;
import com.quanta.demo0.comment.mapper.CommentMapper;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.comment.service.impl.BotCommentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * bot 只读三接口单元测试（C-2）：回复链遍历、parentId 归一、图片空数组与分页。
 */
@ExtendWith(MockitoExtension.class)
class BotCommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private ContentQueryService contentQueryService;

    @InjectMocks
    private BotCommentServiceImpl botCommentService;

    private static ContentComment comment(Long id, Long parentId, Long userId, String content) {
        return ContentComment.builder()
                .commentId(id)
                .contentId(9001L)
                .parentId(parentId)
                .replyCommentId(null)
                .replyUserId(null)
                .userId(userId)
                .content(content)
                .auditStatus(1)
                .isDeleted(0)
                .createTime(LocalDateTime.of(2026, 9, 18, 10, 0).plusMinutes(id))
                .build();
    }

    @Test
    void chainFollowsDirectReplyThenFallsBackToFloorInTimeOrder() {
        ContentComment trigger = comment(9103L, 9101L, 42L, "@框框 选课求指点");
        trigger.setReplyCommentId(9102L);
        ContentComment middle = comment(9102L, 9101L, 7L, "同问");
        ContentComment top = comment(9101L, 0L, 7L, "求助选课");
        when(commentMapper.selectVisibleById(9103L)).thenReturn(trigger);
        when(commentMapper.selectVisibleById(9102L)).thenReturn(middle);
        when(commentMapper.selectVisibleById(9101L)).thenReturn(top);
        when(contentQueryService.getContentSnapshot(9001L)).thenReturn(ContentSnapshotVO.builder()
                .contentId(9001L)
                .contentType(1)
                .title("选课帖")
                .content("主楼内容")
                .publishUserId(7L)
                .auditStatus(1)
                .isDeleted(0)
                .build());
        when(commentMapper.selectImagesByCommentIds(anyList())).thenReturn(List.of(
                CommentImage.builder().commentId(9103L).imageUrl("https://img/1.png").build()));

        BotCommentChainVO result = botCommentService.getChain(9103L);

        assertThat(result.getPost().getPostId()).isEqualTo(9001L);
        assertThat(result.getPost().getUserId()).isEqualTo(7L);
        assertThat(result.getPost().getTitle()).isEqualTo("选课帖");
        assertThat(result.getChain()).extracting("commentId")
                .containsExactly(9101L, 9102L, 9103L);
        assertThat(result.getChain().get(0).getParentId()).isNull();
        assertThat(result.getChain().get(2).getImages()).containsExactly("https://img/1.png");
        assertThat(result.getChain().get(0).getImages()).isNotNull().isEmpty();
    }

    @Test
    void chainRejectsInvisibleTriggerComment() {
        when(commentMapper.selectVisibleById(999L)).thenReturn(null);

        assertThatThrownBy(() -> botCommentService.getChain(999L))
                .isInstanceOf(CommentFailedException.class)
                .hasMessageContaining("评论不存在");
    }

    @Test
    void historyReturnsPageWithTotalAndNonNullImages() {
        when(commentMapper.countBotHistory(10000L, 9001L)).thenReturn(3L);
        when(commentMapper.selectBotHistory(anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(comment(9101L, null, 10000L, "bot 上一条")));
        when(commentMapper.selectImagesByCommentIds(anyList())).thenReturn(List.of());

        BotCommentHistoryVO result = botCommentService.getHistory(10000L, 9001L, 1, 50);

        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getUserId()).isEqualTo(10000L);
        assertThat(result.getList().get(0).getImages()).isNotNull().isEmpty();
    }

    @Test
    void treeReturnsTopLevelAndNestedCommentsInAscendingOrder() {
        when(commentMapper.countBotFloors(9001L)).thenReturn(2L);
        when(commentMapper.selectBotFloorsAsc(anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        comment(9101L, null, 7L, "一级"),
                        comment(9102L, 9101L, 8L, "楼中楼")));
        when(commentMapper.selectImagesByCommentIds(anyList())).thenReturn(List.of());

        BotCommentTreeVO result = botCommentService.getTree(9001L, 1, 50, "asc");

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getList()).extracting("commentId").containsExactly(9101L, 9102L);
        assertThat(result.getList().get(1).getParentId()).isEqualTo(9101L);
    }

    @Test
    void treeUsesDescendingQueryAndNormalizesUnknownSortToAscending() {
        when(commentMapper.countBotFloors(9001L)).thenReturn(0L);
        when(commentMapper.selectBotFloorsDesc(anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of());

        assertThat(botCommentService.getTree(9001L, 1, 50, "desc").getList()).isEmpty();

        when(commentMapper.selectBotFloorsAsc(anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of());
        assertThat(botCommentService.getTree(9001L, 1, 50, "weird").getList()).isEmpty();
    }
}
