package com.quanta.demo0.search.mapper;

import com.quanta.demo0.search.entity.SearchHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索历史落库 Mapper（tb_user_search_history 表）。
 *
 * ============================================================
 * 【为什么热搜词榜只靠这张表 + 一条 GROUP BY 就够了？】
 * ============================================================
 * 写入侧把同一 (userId, keyword) 压成一行、重复搜索只刷 create_time
 * （见 ContentSearchServiceImpl.recordHistory），于是 COUNT(*) 天然等于
 * "该词被多少个用户搜过"——按用户去重的热度，一个人反复搜同一个词刷不了榜。
 * selectHotKeywords 对全站 is_deleted=0 记录 GROUP BY keyword，
 * 按次数降序、再按最近时间降序取 TopN；热门问题/热门校友榜则走 Redis ZSET
 * 排行，是另一套热度口径。简单 SQL 用注解写在接口上，
 * 多行语句放 resources/mapper/search/SearchMapper.xml。
 */
@Mapper
public interface SearchMapper {
    /**
     * 查当前用户是否搜过某关键词。
     *
     * <p>【坑】带 is_deleted=0 条件：用户清空历史后再搜同一个词，
     * 这里查不到旧行 → 走 insert 新行，软删的历史不会被"复活"。</p>
     */
    @Select("select * from tb_user_search_history where user_id = #{userId} and keyword = #{keyword} and is_deleted = 0")
    SearchHistory selectByUserIdAndKeyword(Long userId, String keyword);

    /**
     * 刷新"最近搜索时间"。
     *
     * <p>【坑】XML 里 set create_time = CURRENT_TIMESTAMP()，
     * 方法入参 now 并没有被 SQL 使用，时间以数据库为准——
     * 应用服务器时钟漂移不会污染历史排序。</p>
     */
    void updateSearchTime(Long userId, String keyword, LocalDateTime now);

    /** 插入一条搜索历史；useGeneratedKeys 回填自增 id。 */
    void insertSearchHistory(SearchHistory searchHistory);

    /** 当前用户全部可见关键词，按 create_time 倒序；去重/截 20 条在 Service 层做。 */
    @Select("select keyword from tb_user_search_history where user_id = #{userId} and is_deleted = 0 order by create_time desc ")
    List<String> selectSearchHistoryKeywords(Long userId);


    /** 清空当前用户历史（软删全部 + 刷 update_time），返回影响行数仅供日志。 */
   int softDeleteAllByUserId(Long userId);

    /** 软删一条历史；SQL 同时限定 id 与 user_id，删不了别人的记录。 */
    int softDeleteSearchHistoryByIdAndUserId(Long id, Long userId);

    /**
     * 查询热门关键词（全站聚合）
     *
     * @param limit 限制数量
     * @return 热门关键词列表
     */
    List<String> selectHotKeywords(@Param("limit") int limit);
}
