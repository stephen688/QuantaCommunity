package com.quanta.demo0.mapper;

import com.quanta.demo0.dto.UserAdminQueryDTO;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.entity.UserAuthInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper {


    //根据openid查询用户
    @Select("select * from tb_user where openid = #{openid}")
    public User getByOpenid(String openid);

    //插入用户
    void insert(User user);

    //根据用户id查询用户认证信息
    @Select("select * from tb_user_auth where user_id = #{userId}")
    UserAuth getUserAuthByUserId(Long userId);

    //插入用户认证信息
    void insertUserAuth(UserAuth userAuth);

    //根据用户id查询用户信息
    @Select("select * from tb_user where id = #{id}")
    User getById(Long id);

    //根据用户id更新用户信息
    int updateById(User user);

    //根据认证id查询用户认证信息
    @Select("select * from tb_user_auth where auth_id = #{authId}")
    UserAuth getUserAuthByAuthId(Long authId);

    //根据认证id更新用户认证信息
    void updateUserAuth(UserAuth auth);

    //根据用户ids列表查询用户基本信息和用户认证信息列表
    List<UserAuthInfo> selectUserAuthInfoByIds(@Param("userIds") List<Long> userIds);

    //根据发布用户id查询用户认证信息(联表查询tb_user和tb_user_auth表，并且过滤软删除用户)

    UserAuthInfo selectUserAuthInfoById(@Param("userId") Long userId);

    /**
     * 管理端 - 分页查询用户列表
     *
     * @param query 查询条件
     * @return 用户列表
     */
    List<User> pageAdmin(@Param("query") UserAdminQueryDTO query);




    /**
     * 查询粉丝数最高的用户（热门校友兜底）
     *
     * @param limit 限制数量
     * @return 用户认证信息列表
     */
    List<UserAuthInfo> selectTopFollowedUsers(@Param("limit") int limit);

    /**
     * 查询用户账号状态。
     *
     * 只查询实时推送需要的account_status字段，
     * 避免为了判断是否封禁而查询整条用户信息。
     *
     * @param userId 用户ID
     * @return 0-正常，1-封禁；用户不存在时返回null
     */
    @Select("""
        select account_status
        from tb_user
        where id = #{userId}
          and is_deleted = 0
        """)
    Integer getAccountStatusById(Long userId);



}


