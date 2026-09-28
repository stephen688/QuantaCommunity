package com.quanta.demo0.content.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanta.demo0.content.entity.Content;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.content.properties.ContentTopicProperties;
import com.quanta.demo0.platform.mq.service.OutboxEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ContentTopicTagServiceImplTest {

    @Mock
    private ContentMapper contentMapper;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private ChatModel chatModel;

    @Test
    void tagsOnlyVisibleContentAndPersistsDistinctControlledTopics() {
        Content content = approvedContent(null);
        when(contentMapper.selectById(7L)).thenReturn(content);
        when(contentMapper.updateTags(7L, "[\"basketball\",\"software_technology\"]")).thenReturn(1);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "[\"篮球\",\"软件技术\",\"篮球\"]"));

        ContentTopicTagServiceImpl service = service();
        service.tagContent(7L);

        verify(contentMapper).updateTags(7L, "[\"basketball\",\"software_technology\"]");
    }

    @Test
    void rejectsUnknownModelTopicSoConsumerCanRetryInsteadOfPersistingFalseTags() {
        when(contentMapper.selectById(7L)).thenReturn(approvedContent(null));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("[\"unknown\"]"));

        ContentTopicTagServiceImpl service = service();

        assertThatThrownBy(() -> service.tagContent(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("受控词表");
        verify(contentMapper, never()).updateTags(any(), any());
    }

    @Test
    void persistsAtMostThreeDistinctTopicsAndAllowsExactlyThree() {
        Content content = approvedContent(null);
        when(contentMapper.selectById(7L)).thenReturn(content);
        when(contentMapper.updateTags(7L, "[\"basketball\",\"football\",\"running\"]")).thenReturn(1);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("[\"篮球\",\"足球\",\"跑步\"]"));

        service().tagContent(7L);

        verify(contentMapper).updateTags(7L, "[\"basketball\",\"football\",\"running\"]");
    }

    @Test
    void rejectsFourDistinctTopicsEvenWhenConfigurationAttemptsToRaiseTheLimit() {
        Content content = approvedContent(null);
        when(contentMapper.selectById(7L)).thenReturn(content);
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "[\"篮球\",\"足球\",\"跑步\",\"游泳\"]"));
        ContentTopicProperties properties = new ContentTopicProperties();
        properties.setEnabled(true);
        properties.setTimeoutSeconds(2);
        properties.setMaxTags(99);

        assertThatThrownBy(() -> service(properties).tagContent(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数量超过上限");
        verify(contentMapper, never()).updateTags(any(), any());
    }

    @Test
    void disabledBackfillDoesNotAdvanceCursorOrCreateOutboxEvents() {
        ContentTopicProperties properties = new ContentTopicProperties();
        properties.setEnabled(false);

        long nextCursor = service(properties).enqueueBackfill(18L, 3);

        assertThat(nextCursor).isEqualTo(18L);
        verify(contentMapper, never()).selectApprovedWithoutTags(anyLong(), anyInt());
        verify(outboxEventService, never()).createContentTopicTagEvent(anyLong());
    }

    @Test
    void invisibleContentRemainsRecoverableWithoutCallingTheModel() {
        when(contentMapper.selectById(7L)).thenReturn(approvedContent("[]", AuditStatus.REJECTED.getCode(), 0));

        ContentTopicTagServiceImpl service = service();
        assertThatThrownBy(() -> service.tagContent(7L)).isInstanceOf(IllegalStateException.class);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(contentMapper, never()).updateTags(any(), any());
    }

    @Test
    void visibilityChangeDuringModelCallCannotBeMarkedSuccessful() {
        when(contentMapper.selectById(7L)).thenReturn(approvedContent(null),
                approvedContent(null, AuditStatus.REJECTED.getCode(), 0));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("[\"basketball\"]"));
        when(contentMapper.updateTags(7L,"[\"basketball\"]")).thenReturn(0);
        assertThatThrownBy(() -> service().tagContent(7L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void treatsAnEmptyStoredArrayAsAlreadyProcessed() {
        when(contentMapper.selectById(7L)).thenReturn(approvedContent("[]"));

        ContentTopicTagServiceImpl service = service();
        service.tagContent(7L);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(contentMapper, never()).updateTags(any(), any());
    }

    @Test
    void skipsContentThatAlreadyHasTagsToKeepRedeliveryIdempotent() {
        Content content = approvedContent("[\"basketball\"]");
        when(contentMapper.selectById(7L)).thenReturn(content);

        ContentTopicTagServiceImpl service = service();
        service.tagContent(7L);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(contentMapper, never()).updateTags(any(), any());
    }

    @Test
    void enqueuesApprovedUntaggedRowsAndReturnsTheLastIdAsNextCursor() {
        when(contentMapper.selectApprovedWithoutTags(10L, 3))
                .thenReturn(List.of(approvedContentWithId(12L), approvedContentWithId(18L)));

        ContentTopicTagServiceImpl service = service();
        long nextCursor = service.enqueueBackfill(10L, 3);

        assertThat(nextCursor).isEqualTo(18L);
        verify(outboxEventService).createContentTopicTagEvent(12L);
        verify(outboxEventService).createContentTopicTagEvent(18L);
    }

    private ContentTopicTagServiceImpl service() {
        ContentTopicProperties properties = new ContentTopicProperties();
        properties.setEnabled(true);
        properties.setTimeoutSeconds(2);
        properties.setMaxTags(3);
        return service(properties);
    }

    private ContentTopicTagServiceImpl service(ContentTopicProperties properties) {
        return new ContentTopicTagServiceImpl(
                contentMapper,
                outboxEventService,
                chatModel,
                new ObjectMapper(),
                properties);
    }

    private ChatResponse response(String text) {
        AssistantMessage output = new AssistantMessage(text);
        Generation generation = new Generation(output);
        return new ChatResponse(List.of(generation));
    }

    private Content approvedContent(String tags) {
        return approvedContent(tags, AuditStatus.APPROVED.getCode(), 0);
    }

    private Content approvedContent(String tags, Integer auditStatus, Integer isDeleted) {
        return Content.builder()
                .contentId(7L)
                .title("篮球与编程学习安排")
                .content("篮球训练后如何安排编程学习")
                .contentType(1)
                .auditStatus(auditStatus)
                .isDeleted(isDeleted)
                .tags(tags)
                .build();
    }

    private Content approvedContentWithId(Long contentId) {
        Content content = approvedContent(null);
        content.setContentId(contentId);
        return content;
    }
}
