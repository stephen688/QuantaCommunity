package com.quanta.demo0.service.Impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.dto.AdminAuditLogQueryDTO;
import com.quanta.demo0.entity.AdminAuditLog;
import com.quanta.demo0.exception.ContentFailedException;
import com.quanta.demo0.mapper.AdminAuditLogMapper;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.service.AdminAuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端审计日志查询服务实现类。
 */
@Service
public class AdminAuditLogServiceImpl
        implements AdminAuditLogService {

    /**
     * 单页最大条数。
     */
    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private AdminAuditLogMapper adminAuditLogMapper;

    @Override
    public PageResult pageQuery(
            AdminAuditLogQueryDTO query
    ) {
        if (query == null) {
            query = new AdminAuditLogQueryDTO();
        }

        // 限制单页最大100条
        Integer pageSize = query.getPageSize();
        if (pageSize == null || pageSize < 1) {
            pageSize = 10;
        } else if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        query.setPageSize(pageSize);

        Integer pageNum = query.getPageNum();
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        query.setPageNum(pageNum);

        PageHelper.startPage(pageNum, pageSize);
        List<AdminAuditLog> list =
                adminAuditLogMapper.pageQuery(query);

        Page<AdminAuditLog> page = (Page<AdminAuditLog>) list;
        return new PageResult(
                page.getTotal(),
                page.getResult()
        );
    }

    @Override
    public AdminAuditLog detail(Long id) {
        AdminAuditLog auditLog =
                adminAuditLogMapper.detail(id);
        if (auditLog == null) {
            throw new ContentFailedException("审计日志不存在");
        }
        return auditLog;
    }
}
