package com.quanta.demo0.identity.service;

import com.quanta.demo0.identity.dto.IdentityAuditDTO;
import com.quanta.demo0.identity.dto.IdentityExamDTO;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.identity.vo.IdentityDetailVO;

public interface IdentityExamService {
    PageResult pageQuery(IdentityExamDTO identityExamDTO);

    IdentityDetailVO getDetailById(Long authId);

    void audit(IdentityAuditDTO identityAuditDTO);
}
