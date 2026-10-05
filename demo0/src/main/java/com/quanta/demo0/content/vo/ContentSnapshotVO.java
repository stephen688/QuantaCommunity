package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 内容域向其他业务域暴露的最小内容快照。
 *
 * ============================================================
 * 【跨域返回 VO 而不是实体：包边界的防腐层】
 * ============================================================
 * feed/search/rag 等域通过 ContentQueryService 拿内容时，拿到的是这个 VO
 * 而不是 Content 实体 —— 实体的字段/注解/懒加载形状是 content 包的私有实现，
 * 一旦跨域泄露，改表结构就得全项目陪跑。**跨域只认快照，实体不出包**。
 *
 * 【为什么没有作者信息和访问者状态】
 * 这是给程序消费的"事实快照"，不是给页面渲染的读模型（那是 ContentVO 的职责）：
 * 作者资料调用方按 publishUserId 自己查（作者维度有独立缓存），
 * 访问者状态与本域无关。字段越少，缓存/序列化/复用的成本越低。
 *
 * 【auditStatus/isDeleted 也在快照里 —— 状态也是事实】
 * 不同调用方对可见性的口径不同：getContentSnapshots 已过滤掉软删/未过审；
 * 但搜索对账用的 getContentFactSnapshots 需要**全量事实**（含被删的）才能校准索引。
 * 过滤与否由调用方决定，快照本身不替人做主。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentSnapshotVO {
    private Long contentId;
    private Integer contentType;
    private String title;
    private String content;
    /** 原始主题 JSON；NULL 与空数组分别表示未处理与无匹配主题。 */
    private String tags;
    private Long publishUserId;
    private Integer auditStatus;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer likedCount;
    private Integer commentCount;
    private Integer collectCount;
}
