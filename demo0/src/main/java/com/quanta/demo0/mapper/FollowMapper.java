package com.quanta.demo0.mapper;

import com.quanta.demo0.entity.Follow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FollowMapper {

    int insert(Follow follow);

    boolean update(Follow follow);

    //void restoreFollow(Follow follow);

    @Select("select * from tb_user_follow where user_id = #{userId} and follow_user_id = #{followUserId} for update")
    Follow selectExistForUpdate(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    @Select("select user_id from tb_user_follow where follow_user_id = #{publishUserId} and is_deleted = 0")
    List<Long> selectFollowerIds(@Param("publishUserId") Long publishUserId);

    /** 当前用户关注的用户 ID 列表 */
    @Select("select follow_user_id from tb_user_follow where user_id = #{userId} and is_deleted = 0")
    List<Long> selectFollowUserIds(@Param("userId") Long userId);


    /**
     * 统计粉丝数（多少人关注了该用户）
     * @param userId 用户 ID
     * @return 粉丝数
     */
    @Select("SELECT COUNT(*) FROM tb_user_follow WHERE follow_user_id = #{userId} AND is_deleted = 0")
    Integer countFollowers(@Param("userId") Long userId);

    /**
     * 统计关注数（该用户关注了多少人）
     * @param userId 用户 ID
     * @return 关注数
     */
    @Select("SELECT COUNT(*) FROM tb_user_follow WHERE user_id = #{userId} AND is_deleted = 0")
    Integer countFollowing(@Param("userId") Long userId);
}
