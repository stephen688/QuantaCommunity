package com.quanta.demo0.service;

import com.quanta.demo0.platform.audit.dto.AdminAuditLogQueryDTO;
import com.quanta.demo0.platform.audit.entity.AdminAuditLog;
import com.quanta.demo0.platform.common.result.PageResult;

/**
 * 管理端审计日志查询服务。
 */
public interface AdminAuditLogService {

    PageResult pageQuery(AdminAuditLogQueryDTO query);

    AdminAuditLog detail(Long id);
}
