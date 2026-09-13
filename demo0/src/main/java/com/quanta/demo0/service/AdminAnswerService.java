package com.quanta.demo0.service;

import com.quanta.demo0.dto.AnswerAdminQueryDTO;
import com.quanta.demo0.dto.ContentAuditDTO;
import com.quanta.demo0.result.PageResult;

public interface AdminAnswerService {
    PageResult pageQuery(AnswerAdminQueryDTO query);

    void deleteAnswer(Long answerId);

    void auditAnswer(ContentAuditDTO auditDTO);
}
