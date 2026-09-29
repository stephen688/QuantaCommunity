package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.comment.service.AdminCommentService;
import com.quanta.demo0.content.service.AdminContentService;
import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.interaction.dto.CommentReportHandleDTO;
import com.quanta.demo0.interaction.dto.ContentReportHandleDTO;
import com.quanta.demo0.interaction.entity.CommentReport;
import com.quanta.demo0.interaction.entity.ContentReport;
import com.quanta.demo0.interaction.mapper.CommentReportMapper;
import com.quanta.demo0.interaction.mapper.ContentInteractionMapper;
import com.quanta.demo0.interaction.mapper.ContentReportMapper;
import com.quanta.demo0.notification.enums.NotificationType;
import com.quanta.demo0.notification.mq.message.NotificationEventMessage;
import com.quanta.demo0.notification.mq.producer.NotificationEventProducer;
import com.quanta.demo0.platform.audit.service.AdminAuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 举报迁移行为契约：更新后同步委托对象删除，通知路由与业务字段不变。 */
class ReportGovernanceServiceImplTest {
    private final ContentReportMapper contentReports = mock(ContentReportMapper.class);
    private final CommentReportMapper commentReports = mock(CommentReportMapper.class);
    private final AdminContentService contents = mock(AdminContentService.class);
    private final AdminCommentService comments = mock(AdminCommentService.class);
    private final NotificationEventProducer notifications = mock(NotificationEventProducer.class);

    @Test
    void contentReportDeleteKeepsUpdateDeleteAndNotificationOrder() {
        when(contentReports.getReportById(7L)).thenReturn(ContentReport.builder()
                .id(7L).contentId(10L).reporterId(8L).build());
        ContentReportHandleDTO request = new ContentReportHandleDTO();
        request.setReportId(7L);
        request.setHandleResult(1);

        service().handleReport(request);

        var order = inOrder(contentReports, contents, notifications);
        order.verify(contentReports).getReportById(7L);
        order.verify(contentReports).updateReport(argThat(report -> report.getStatus() == 2
                && report.getId().equals(7L) && report.getHandleResult() == 1));
        order.verify(contents).deleteContent(10L);
        ArgumentCaptor<NotificationEventMessage> message = ArgumentCaptor.forClass(NotificationEventMessage.class);
        order.verify(notifications).createNotificationEvent(message.capture(), eq("CONTENT"), eq(10L));
        assertThat(message.getValue().getType()).isEqualTo(NotificationType.CONTENT_REPORT_RESULT.getCode());
        assertThat(message.getValue().getRecipientUserId()).isEqualTo(8L);
        assertThat(message.getValue().getPayload()).containsEntry("reportId", 7L).containsEntry("contentId", 10L);
        verifyNoInteractions(comments);
    }

    @Test
    void commentReportWarningDoesNotDeleteAndKeepsNotificationPayload() {
        when(commentReports.getReportById(9L)).thenReturn(CommentReport.builder()
                .id(9L).commentId(20L).reporterId(8L).build());
        CommentReportHandleDTO request = new CommentReportHandleDTO();
        request.setReportId(9L);
        request.setHandleResult(2);

        service().handleReport(request);

        verify(commentReports).updateReport(argThat(report -> report.getStatus() == 2
                && report.getId().equals(9L) && report.getHandleResult() == 2));
        verifyNoInteractions(comments, contents);
        ArgumentCaptor<NotificationEventMessage> message = ArgumentCaptor.forClass(NotificationEventMessage.class);
        verify(notifications).createNotificationEvent(message.capture(), eq("COMMENT"), eq(20L));
        assertThat(message.getValue().getType()).isEqualTo(NotificationType.COMMENT_REPORT_RESULT.getCode());
        assertThat(message.getValue().getRecipientUserId()).isEqualTo(8L);
        assertThat(message.getValue().getPayload()).containsEntry("reportId", 9L).containsEntry("commentId", 20L);
    }

    private ReportGovernanceServiceImpl service() {
        return new ReportGovernanceServiceImpl(mock(ContentCounterService.class),
                mock(ContentInteractionMapper.class), contentReports, commentReports, comments,
                contents, notifications, mock(AdminAuditRecorder.class));
    }
}
