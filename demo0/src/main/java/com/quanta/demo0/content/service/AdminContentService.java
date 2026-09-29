package com.quanta.demo0.content.service;

import com.quanta.demo0.content.dto.ContentAdminQueryDTO;
import com.quanta.demo0.content.dto.ContentAuditDTO;
import com.quanta.demo0.platform.common.result.PageResult;

public interface AdminContentService {
    PageResult pageQuery(ContentAdminQueryDTO query);

    void audit(ContentAuditDTO auditDTO);

    void deleteContent(Long contentId);

}
