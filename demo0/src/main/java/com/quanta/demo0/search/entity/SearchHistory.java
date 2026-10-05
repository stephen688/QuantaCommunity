package com.quanta.demo0.search.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户搜索历史实体（tb_user_search_history 表）。
 *
 * ============================================================
 * 【为什么"最近搜索时间"存在 create_time 上？】
 * ============================================================
 * 写入侧（ContentSearchServiceImpl.recordHistory）把同一 (userId, keyword)
 * 压成一行：已存在则执行 updateSearchTime 刷新 create_time（SQL 里直接取
 * 数据库的 CURRENT_TIMESTAMP()，方法入参 now 并不进 SQL），不存在才 insert。
 * 于是 create_time 的实际语义是"最近一次搜索时间"，历史页与热搜榜
 * （selectHotKeywords 的 MAX(COALESCE(update_time, create_time))）都基于它排序；
 * update_time 只在软删除时被顺手维护。写入方只有搜索行为本身，
 * 读取/删除方是用户自己的历史接口。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchHistory implements Serializable {
    /** 主键，insert 时由 useGeneratedKeys 回填（SearchMapper.xml insertSearchHistory）。 */
    private Long id;
    /** 归属用户；所有查询/删除 SQL 都带 user_id 条件，用户只能看见和删自己的历史。 */
    private Long userId;
    /** 用户输入的关键词（trim 后），同时也是热搜榜 selectHotKeywords 的聚合维度。 */
    private String keyword;
    /** 软删标识 0-正常 1-已删除；清空/删除单条都是置 1，物理行保留（见 SearchMapper.xml）。 */
    private Integer isDeleted;
    /** 插入时间，被复用为"最近一次搜索时间"（重复搜索同词时被刷新，见类注释）。 */
    private LocalDateTime createTime;
    /** 仅在软删除时随 SQL 更新（set update_time = CURRENT_TIMESTAMP()）。 */
    private LocalDateTime updateTime;
}