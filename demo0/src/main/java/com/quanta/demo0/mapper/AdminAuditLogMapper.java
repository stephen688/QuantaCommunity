package com.quanta.demo0.mapper;

import com.quanta.demo0.dto.AdminAuditLogQueryDTO;
import com.quanta.demo0.entity.AdminAuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 管理员审计日志数据访问接口。
 */
@Mapper
public interface AdminAuditLogMapper {

    @Insert("""
            insert into admin_audit_log (
                request_id, operator_id, operator_roles,
                action, target_type, target_id,
                http_method, request_path,
                before_summary, after_summary,
                result_status, error_code, error_message,
                client_ip, user_agent, created_at
            ) values (
                #{requestId}, #{operatorId}, #{operatorRoles},
                #{action}, #{targetType}, #{targetId},
                #{httpMethod}, #{requestPath},
                #{beforeSummary}, #{afterSummary},
                #{resultStatus}, #{errorCode}, #{errorMessage},
                #{clientIp}, #{userAgent}, #{createdAt}
            )
            """)
    @Options(
            useGeneratedKeys = true,
            keyProperty = "id"
    )
    int insert(AdminAuditLog auditLog);

    /**
     * 分页查询审计日志（动态SQL在XML中）。
     */
    List<AdminAuditLog> pageQuery(
            @Param("query") AdminAuditLogQueryDTO query
    );

    /**
     * 统计符合条件的审计日志数量（动态SQL在XML中）。
     */
    long countQuery(
            @Param("query") AdminAuditLogQueryDTO query
    );

    /**
     * 查询单条审计日志详情。
     */
    @Select("""
            select *
            from admin_audit_log
            where id = #{id}
            """)
    AdminAuditLog detail(Long id);



}
