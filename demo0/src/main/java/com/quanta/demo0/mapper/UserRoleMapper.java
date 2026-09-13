package com.quanta.demo0.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户角色数据访问接口。
 *
 * 当前只负责根据用户ID读取管理角色。
 */
@Mapper
public interface UserRoleMapper {

    /**
     * 查询用户拥有的全部管理角色代码。
     *
     * @param userId 用户ID
     * @return 角色代码列表
     */
    @Select(
            "select role_code " +
                    "from user_role " +
                    "where user_id = #{userId} " +
                    "order by role_code"
    )
    List<String> findRoleCodesByUserId(
            @Param("userId") Long userId
    );


    /**
     * 为用户授予管理角色。
     *
     * @param userId 用户ID
     * @param roleCode 角色代码
     * @param createdBy 创建人ID
     * @return 受影响的行数
     */
    @Insert("""
        insert ignore into user_role (
            user_id, role_code, created_by
        ) values (
            #{userId}, #{roleCode}, #{createdBy}
        )
        """)
    int grantRole(
            @Param("userId") Long userId,
            @Param("roleCode") String roleCode,
            @Param("createdBy") Long createdBy
    );

    /**
     * 从用户角色中移除管理角色。
     *
     * @param userId 用户ID
     * @param roleCode 角色代码
     * @return 受影响的行数
     */
    @Delete("""
        delete from user_role
        where user_id = #{userId}
          and role_code = #{roleCode}
        """)
    int revokeRole(
            @Param("userId") Long userId,
            @Param("roleCode") String roleCode
    );

}