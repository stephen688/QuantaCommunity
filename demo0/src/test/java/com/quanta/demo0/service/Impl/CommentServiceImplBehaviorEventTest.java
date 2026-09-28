package com.quanta.demo0.service.Impl;

import com.quanta.demo0.dto.CommentAddDTO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.entity.Content;
import com.quanta.demo0.entity.ContentComment;
import com.quanta.demo0.exception.CommentFailedException;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.policy.CommentZonePolicy;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.platform.security.properties.QuantabotProperties;
import com.quanta.demo0.service.CommentAuditService;
import com.quanta.demo0.service.ContentDetailCacheInvalidator;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.utils.SensitiveWordChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * sendComment 的画像行为事件挂载测试（推荐流个性化 D2）。
 * 断言：只有用户新增评论成功才创建 COMMENT 行为 Outbox 事件，
 * 且事件创建与评论插入发生在同一方法内（同层即同事务）；
 * 敏感词拦截、帖子不存在等失败路径零调用；管理员删除/审核驳回路径不经过本方法。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceImplBehaviorEventTest {

    private static final Long USER_ID = 3L;
    private static final Long AUTHOR_ID = 999L;
    private static final Long CONTENT_ID = 10L;
    private static final Long COMMENT_ID = 100L;

    @Mock
    private CommentMapper commentMapper;
    @Mock
    private ContentMapper contentMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private SensitiveWordChecker sensitiveWordChecker;
    @Mock
    private CommentZonePolicy commentZonePolicy;
    @Mock
    private AliyunModerationProperties moderationProperties;
    @Mock
    private AliyunModerationProperties.Targets targets;
    @Mock
    private AliyunModerationProperties.TargetConfig commentConfig;
    @Mock
    private CommentAuditService commentAuditService;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private ContentDetailCacheInvalidator contentDetailCacheInvalidator;

    @InjectMocks
    private CommentServiceImpl service;

    private final QuantabotProperties quantabotProperties = new QuantabotProperties();

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(USER_ID);
        ReflectionTestUtils.setField(service, "quantabotProperties", quantabotProperties);
        // 审核总闸关闭且关闭策略非 APPROVED：走纯新增评论路径，
        // 不触发审核 Outbox 分支和自动通过分支，聚焦行为事件断言。
        when(moderationProperties.isEnabled()).thenReturn(false);
        when(moderationProperties.getTargets()).thenReturn(targets);
        when(targets.getComment()).thenReturn(commentConfig);
        when(commentConfig.getDisabledPolicy()).thenReturn("PENDING");
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    private CommentAddDTO commentAddDTO(String text) {
        return CommentAddDTO.builder()
                .contentId(CONTENT_ID)
                .parentId(null)
                .replyCommentId(null)
                .replyUserId(null)
                .content(text)
                .imageUrls(null)
                .build();
    }

    private void stubApprovedLifeContent(String text) {
        Content content = Content.builder()
                .contentId(CONTENT_ID)
                .contentType(1)
                .auditStatus(1)
                .publishUserId(AUTHOR_ID)
                .build();
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(content);
        when(commentZonePolicy.getMaxLength(1)).thenReturn(500);
        when(sensitiveWordChecker.findFirstHit(text)).thenReturn(null);
    }

    @Test
    void 发表评论成功_发COMMENT行为事件_且在评论插入之后() {
        stubApprovedLifeContent("这条帖子写得真好");
        doAnswer(invocation -> {
            ContentComment comment = invocation.getArgument(0);
            comment.setCommentId(COMMENT_ID);
            return null;
        }).when(commentMapper).insert(any(ContentComment.class));

        Long result = service.sendComment(commentAddDTO("这条帖子写得真好"));

        assertEquals(COMMENT_ID, result);
        verify(outboxEventService).createUserBehaviorEvent(USER_ID, CONTENT_ID, "COMMENT");
        // 事件创建必须发生在评论插入之后、同一方法内——与审核 Outbox 同层，即同一事务。
        var inOrder = inOrder(commentMapper, outboxEventService);
        inOrder.verify(commentMapper).insert(any(ContentComment.class));
        inOrder.verify(outboxEventService).createUserBehaviorEvent(USER_ID, CONTENT_ID, "COMMENT");
    }

    @Test
    void 敏感词拦截_零调用行为事件() {
        stubApprovedLifeContent("包含敏感词的内容");
        when(sensitiveWordChecker.findFirstHit("包含敏感词的内容")).thenReturn("敏感词");

        assertThrows(CommentFailedException.class,
                () -> service.sendComment(commentAddDTO("包含敏感词的内容")));

        verify(commentMapper, never()).insert(any(ContentComment.class));
        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 帖子不存在_零调用行为事件() {
        when(contentMapper.selectById(CONTENT_ID)).thenReturn(null);

        assertThrows(CommentFailedException.class,
                () -> service.sendComment(commentAddDTO("内容不存在时发布")));

        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }

    @Test
    void 评论插入未回填ID_零调用行为事件() {
        stubApprovedLifeContent("插入成功但未回填主键");
        // insert 不回填 commentId，防御路径必须抛异常且不发行为事件
        doAnswer(invocation -> null).when(commentMapper).insert(any(ContentComment.class));

        assertThrows(CommentFailedException.class,
                () -> service.sendComment(commentAddDTO("插入成功但未回填主键")));

        verify(outboxEventService, never()).createUserBehaviorEvent(any(), any(), any());
    }
}
