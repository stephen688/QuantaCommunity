package com.quanta.demo0.interaction.mapper;

import com.quanta.demo0.interaction.entity.ContentCollect;
import com.quanta.demo0.interaction.entity.ContentLiked;
import com.quanta.demo0.interaction.entity.ContentReport;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ContentInteractionMapper {

    int insertContentLiked(ContentLiked contentLiked);

    @Select("select count(1) from tb_content_like where content_id = #{contentId} and user_id = #{userId}")
    int countContentLiked(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Delete("delete from tb_content_like where content_id = #{contentId} and user_id = #{userId}")
    int deleteContentLikedByUser(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Delete("delete from tb_content_like where content_id = #{contentId}")
    void deleteContentLikedByContentId(Long contentId);

    int insertCollect(ContentCollect contentCollect);

    @Select("select count(1) from tb_content_collect where content_id = #{contentId} and user_id = #{userId}")
    int countContentCollect(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Delete("delete from tb_content_collect where content_id = #{contentId} and user_id = #{userId}")
    int deleteCollect(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Delete("delete from tb_content_collect where content_id = #{contentId}")
    void deleteContentCollectByContentId(Long contentId);

    @Select("SELECT * FROM tb_content_report WHERE content_id = #{contentId} AND reporter_id = #{reporterId} AND is_deleted = 0")
    ContentReport selectValidReportByContentAndUser(@Param("contentId") Long contentId,
                                                    @Param("reporterId") Long reporterId);

    void insertContentReport(ContentReport report);
}
