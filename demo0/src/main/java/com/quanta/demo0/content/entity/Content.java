package com.quanta.demo0.content.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容实体类
 *
 * 帖子发布域的核心实体，映射 tb_content 表。整条内容链路——发布 → 阿里云内容安全审核
 * （moderation 包）→ 详情多级缓存（ContentDetailCacheServiceImpl）→ MQ 事件
 * （ContentEventProducer + platform 包 Outbox）→ ES 索引（search 包）→ 向量同步（rag 包）
 * → QuantaBot 同步（BotContentSyncMapper）——都以这个实体为数据源头。
 *
 * ============================================================
 * 【为什么逻辑删除（is_deleted）而不是物理 DELETE？】
 * ============================================================
 * 帖子删掉后，下游不止一个消费方需要知道"它没了"：
 *   - search 包全量重建索引时，要把不可见帖子从 ES 里删掉（见 selectAllForReindex 的用法）；
 *   - rag / QuantaBot 同步靠 update_time 变化 + is_deleted=1 把删除当作事件传播出去
 *     （见 BotContentSyncMapper：WHERE audit_status=1 OR is_deleted=1，删除也是要同步的状态）。
 * 物理删除会让这些下游失去"墓碑"，永远不知道该删什么 —— **删除必须留下痕迹才能被同步**。
 *
 * ============================================================
 * 【实体里混着两类字段：数据库列 vs 读模型补丁】
 * ============================================================
 * isLiked / isCollected / esSearchScore 不是 tb_content 的列：
 *   - isLiked / isCollected 是"当前访问者"的私有状态，每次请求单独计算，
 *     被刻意排除在共享详情快照之外（判断标准见 ContentDetailDataLoader 的类注释）；
 *   - esSearchScore 由 search 包拼装检索结果时塞进来，用于按相关性排序，不落库、不序列化。
 * 读代码时要能区分：**只有其余字段才对应表列**，写库路径（insert/update）不会碰这三个。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Content implements Serializable {

    /**
     * 内容唯一 ID（主键）
     *
     * XML 的 insert 配了 useGeneratedKeys：自增值回填到这里，发布事务里
     * 随后的 batchInsertImages、Outbox 登记都依赖这个回填值。
     */
    private Long contentId;

    /**
     * 内容类型：1-生活求助 2-专业问答
     *
     * 推荐流召回、ES 文档、QuantaBot 同步都按它区分处理口径，是贯穿全链路的分流字段。
     */
    private Integer contentType;

    /**
     * 问题标题（限制 50 字以内）
     */
    private String title;

    /**
     * 问题描述（限制 500 字以内）
     */
    private String content;

    /**
     * LLM 主题标签 JSON 数组；NULL 表示尚未处理，[] 表示已处理但未命中主题。
     *
     * 由主题标签异步链路写入：MQ 消费（ContentTopicTagConsumer）调 LLM 命中受控词表后，
     * 经 updateTags 条件写回。NULL 与 [] 语义不同是这条链路的关键：
     * NULL 可重试，[] 是终态（再重试只会白烧模型费用）——见 ContentTopicTagServiceImpl。
     */
    private String tags;

    /**
     * 发布用户 ID（关联 tb_user.user_id）
     *
     * 作者维度的锚点：个人主页帖子列表、关注流回填、缓存负结果 INVALID_AUTHOR 判断都用它。
     */
    private Long publishUserId;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     *
     * 对外可见口径 = audit_status=1 且 is_deleted=0。状态迁移只能走 CAS：
     * updateAuditStatusIfPending 只允许改待审核（0）的行，防止 AI 审核与管理员审核
     * 同时成功（见 ContentAuditServiceImpl）。
     */
    private Integer auditStatus;

    /**
     * 点赞数
     *
     * 由 ContentCounterServiceImpl 以 delta（+1/-1）方式增减，SQL 内原子自增，
     * GREATEST(0, liked + #{i}) 钳住下限 —— 并发下不会互相覆盖，也不会出现负数。
     */
    private Integer liked;

    /**
     * 点赞高亮
     *
     * 【非表列】"当前这个访问者是否点过赞"，查询层在命中共享缓存后单独回填。
     */
    private Boolean isLiked;

    /**
     * 收藏状态
     *
     * 【非表列】含义同 isLiked：访问者私有状态，不进共享详情快照。
     */
    private Boolean isCollected;

    /**
     * 收藏数
     *
     * 计数口径同 liked，走 updateCollectCount 原子增减。
     */
    private Integer collectCount;

    /**
     * 评论数
     *
     * 由 comment 域在评论可见性变化时经 ContentCounterServiceImpl 传 ±1，
     * 走 updateCommentCount 的原子增减（GREATEST 防负）。
     */
    private Integer commentCount;

    /**
     * 发布时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 更新时间
     *
     * 也是增量同步的水位线：rag / QuantaBot 拉变更都用 update_time >= since，
     * 所以任何写库路径都应刷新它（多数 SQL 直接写 now()/CURRENT_TIMESTAMP）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /**
     * 软删除标识：0-未删除 1-已删除
     *
     * 全库查询默认带 is_deleted=0；置 1 走 softDeleteContent（同时刷新 update_time
     * 以触发下游同步），见类注释里"为什么逻辑删除"。
     */
    private Integer isDeleted;

    // 添加：
    /**
     * ES 检索得分（仅用于检索，不落库、不序列化）
     *
     * 【非表列】由 search 包在拼装 ES 命中结果时赋值，列表按它重排相关性；
     * MyBatis 查不到这个列，直查库时保持 null。
     */
   @JsonIgnore
    private Double esSearchScore;
}
