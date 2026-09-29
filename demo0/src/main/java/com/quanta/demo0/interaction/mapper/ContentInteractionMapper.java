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

    @Delete("delete from tb_content_like where content_id = #{contentId} and user_id = #{userId}")
    int deleteContentLikedByUser(@Param("contentId") Long contentId, @Param("userId") Long userId);

    int insertCollect(ContentCollect contentCollect);

    @Delete("delete from tb_content_collect where content_id = #{contentId} and user_id = #{userId}")
    int deleteCollect(@Param("contentId") Long contentId, @Param("userId") Long userId);

    @Select("SELECT * FROM tb_content_report WHERE content_id = #{contentId} AND reporter_id = #{reporterId} AND is_deleted = 0")
    ContentReport selectValidReportByContentAndUser(@Param("contentId") Long contentId,
                                                    @Param("reporterId") Long reporterId);

    void insertContentReport(ContentReport report);
}
