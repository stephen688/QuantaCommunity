package com.quanta.demo0.mapper;

import com.quanta.demo0.entity.SearchHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SearchMapper {
    @Select("select * from tb_user_search_history where user_id = #{userId} and keyword = #{keyword} and is_deleted = 0")
    SearchHistory selectByUserIdAndKeyword(Long userId, String keyword);

    void updateSearchTime(Long userId, String keyword, LocalDateTime now);

    void insertSearchHistory(SearchHistory searchHistory);

    @Select("select keyword from tb_user_search_history where user_id = #{userId} and is_deleted = 0 order by create_time desc ")
    List<String> selectSearchHistoryKeywords(Long userId);


   int softDeleteAllByUserId(Long userId);

    int softDeleteSearchHistoryByIdAndUserId(Long id, Long userId);

    /**
     * 查询热门关键词（全站聚合）
     *
     * @param limit 限制数量
     * @return 热门关键词列表
     */
    List<String> selectHotKeywords(@Param("limit") int limit);
}
