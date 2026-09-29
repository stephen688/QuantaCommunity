package com.quanta.demo0.interaction.service;

import com.quanta.demo0.interaction.dto.ContentReportDTO;
import com.quanta.demo0.interaction.dto.CommentReportHandleDTO;
import com.quanta.demo0.interaction.dto.CommentReportQueryDTO;
import com.quanta.demo0.interaction.dto.ContentReportHandleDTO;
import com.quanta.demo0.interaction.dto.ContentReportQueryDTO;
import com.quanta.demo0.platform.common.result.PageResult;

/** 互动域举报提交与管理治理接口；管理 HTTP 门面保持在原 Controller。 */
public interface ReportGovernanceService {

    void reportContent(ContentReportDTO contentReportDTO);

    PageResult pageReport(ContentReportQueryDTO query);

    void handleReport(ContentReportHandleDTO handleDTO);

    PageResult pageReport(CommentReportQueryDTO query);

    void handleReport(CommentReportHandleDTO handleDTO);
}
