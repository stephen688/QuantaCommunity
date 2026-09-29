package com.quanta.demo0.interaction.service.impl;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.content.service.ContentCounterService;
import com.quanta.demo0.interaction.dto.ContentReportDTO;
import com.quanta.demo0.interaction.entity.ContentReport;
import com.quanta.demo0.interaction.mapper.ContentInteractionMapper;
import com.quanta.demo0.interaction.service.ReportGovernanceService;
import com.quanta.demo0.platform.security.context.BaseContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportGovernanceServiceImpl implements ReportGovernanceService {

    private final ContentCounterService contentCounterService;
    private final ContentInteractionMapper contentInteractionMapper;

    @Override
    @Transactional
    public void reportContent(ContentReportDTO contentReportDTO) {
        if (contentReportDTO.getContentId() == null) {
            throw new ContentFailedException("帖子 ID 不能为空");
        }
        if (contentReportDTO.getReportType() == null
                || contentReportDTO.getReportType() < 1
                || contentReportDTO.getReportType() > 5) {
            throw new ContentFailedException("举报类型不合法（1-垃圾广告 2-人身攻击 3-违规内容 4-虚假信息 5-其他）");
        }
        if (contentCounterService.getContentSnapshot(contentReportDTO.getContentId()) == null) {
            throw new ContentFailedException("帖子不存在");
        }

        Long reporterId = BaseContext.getCurrentId();
        ContentReport existing = contentInteractionMapper.selectValidReportByContentAndUser(
                contentReportDTO.getContentId(), reporterId);
        if (existing != null) {
            throw new ContentFailedException("您已举报过该帖子，请勿重复举报");
        }

        LocalDateTime now = LocalDateTime.now();
        ContentReport report = ContentReport.builder()
                .contentId(contentReportDTO.getContentId())
                .reportType(contentReportDTO.getReportType())
                .reporterId(reporterId)
                .status(0)
                .isDeleted(0)
                .createTime(now)
                .updateTime(now)
                .build();
        contentInteractionMapper.insertContentReport(report);
    }
}
