package com.quanta.demo0.service;

import com.quanta.demo0.answer.dto.AnswerAdminQueryDTO;
import com.quanta.demo0.content.dto.ContentAuditDTO;
import com.quanta.demo0.platform.common.result.PageResult;

public interface AdminAnswerService {
    PageResult pageQuery(AnswerAdminQueryDTO query);

    void deleteAnswer(Long answerId);

    void auditAnswer(ContentAuditDTO auditDTO);
}
