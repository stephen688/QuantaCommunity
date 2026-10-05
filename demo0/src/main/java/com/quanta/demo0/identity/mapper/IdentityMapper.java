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

/**
 * （补充）tb_user_auth 的读写都走这里：查询用注解 SQL，写操作在 XML 动态 SQL 里
 * （resources/mapper/identity/IdentityMapper.xml）。
 *
 * ============================================================
 * 【为什么写操作要放 XML 做动态 SQL？】
 * ============================================================
 * insertUserAuth / updateUserAuth 的每个字段都包着 &lt;if test="... != null"&gt;：
 * 新增时只插有值的列，更新时只更新非空字段。**收益**是用户重提（IdentityServiceImpl）
 * 和管理员审核（IdentityExamServiceImpl）可以共用同一条 UPDATE，各自只动自己的字段；
 * **代价**是"传 null 清空字段"永远不生效——想清掉的列会被 &lt;if&gt; 静默跳过，
 * 这个坑在 IdentityServiceImpl#addUserAuth 的重提分支真实存在。
 */
@Mapper
public interface IdentityMapper {

    /**
     * 按用户读取认证记录。
     *
     * @param userId 用户 ID
     * @return 认证记录，不存在时返回 null
     */
    // 【关键】一个用户只允许存在一条认证记录，所以返回单个实体而非 List：
    // 提交/重提/审核的所有状态判断都基于这次查询（IdentityServiceImpl#addUserAuth）
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
    // 【坑】XML 里 &lt;if&gt; 只拼非空字段：auth 里为 null 的属性不参与 UPDATE，
    // 因此这个方法天然是"补丁语义"，永远不能靠传 null 来清空某一列
    void updateUserAuth(UserAuth auth);
}
