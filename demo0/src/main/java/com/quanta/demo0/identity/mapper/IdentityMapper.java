package com.quanta.demo0.identity.mapper;

import com.quanta.demo0.identity.entity.UserAuth;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 身份认证持久化 Mapper。
 *
 * 职责：读写 tb_user_auth；
 * 边界：用户账号和公开用户资料由 user 域 Mapper 负责。
 */
@Mapper
public interface IdentityMapper {

    /**
     * 按用户读取认证记录。
     *
     * @param userId 用户 ID
     * @return 认证记录，不存在时返回 null
     */
    @Select("select * from tb_user_auth where user_id = #{userId}")
    UserAuth getUserAuthByUserId(@Param("userId") Long userId);

    /**
     * 插入认证申请。
     *
     * @param userAuth 认证记录
     */
    void insertUserAuth(UserAuth userAuth);

    /**
     * 按认证记录 ID 查询。
     *
     * @param authId 认证记录 ID
     * @return 认证记录，不存在时返回 null
     */
    @Select("select * from tb_user_auth where auth_id = #{authId}")
    UserAuth getUserAuthByAuthId(@Param("authId") Long authId);

    /**
     * 更新认证记录。
     *
     * @param auth 认证记录补丁
     */
    void updateUserAuth(UserAuth auth);
}
