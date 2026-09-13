package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.AdminAuditLog;
import com.quanta.demo0.mapper.AdminAuditLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员审计失败记录写入器。
 *
 * 使用独立新事务保存失败日志，
 * 保证原业务事务回滚后失败痕迹仍能保留。
 */
@Service
public class AdminAuditFailureWriter {

    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeFailed(AdminAuditLog auditLog) {
        adminAuditLogMapper.insert(auditLog);
    }
}
