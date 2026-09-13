package com.quanta.demo0.service;

import com.quanta.demo0.dto.ContentAdminQueryDTO;
import com.quanta.demo0.dto.ContentAuditDTO;
import com.quanta.demo0.dto.ContentReportHandleDTO;
import com.quanta.demo0.dto.ContentReportQueryDTO;
import com.quanta.demo0.result.PageResult;

public interface AdminContentService {
    PageResult pageQuery(ContentAdminQueryDTO query);

    void audit(ContentAuditDTO auditDTO);

    void deleteContent(Long contentId);

    PageResult pageReport(ContentReportQueryDTO query);

    void handleReport(ContentReportHandleDTO handleDTO);
}
