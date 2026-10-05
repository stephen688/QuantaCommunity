package com.quanta.demo0.platform.security.mapper;

import com.quanta.demo0.platform.security.entity.AdminCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理凭据查询：按唯一规范账号读取，不负责用户/角色业务或会话签发。 */
@Mapper
public interface AdminCredentialMapper {
    /** 查询凭据，包括禁用状态；未登记账号返回 null。 */
    @Select("SELECT user_id,username,password_hash,enabled FROM admin_credential WHERE username=#{username}")
    AdminCredential findByUsername(@Param("username") String username);
}
