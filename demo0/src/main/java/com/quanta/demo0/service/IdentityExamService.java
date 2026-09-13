package com.quanta.demo0.service;

import com.quanta.demo0.dto.IdentityAuditDTO;
import com.quanta.demo0.dto.IdentityExamDTO;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.vo.IdentityDetailVO;

public interface IdentityExamService {
    PageResult pageQuery(IdentityExamDTO identityExamDTO);

    IdentityDetailVO getDetailById(Long authId);

    void audit(IdentityAuditDTO identityAuditDTO);
}
