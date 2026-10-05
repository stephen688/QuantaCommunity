package com.quanta.demo0.content.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容视图对象
 *
 * ============================================================
 * 【为什么需要 VO：一行 DB 记录 ≠ 一次页面渲染】
 * ============================================================
 * Content 实体只是 tb_content 单表 + 图片表的形状；而详情页/列表页需要
 * 把**三路数据**拼进一个对象：内容本身（MySQL）、作者资料（头像/昵称/部门/届，
 * 来自 user 域的 UserAuthInfoVO）、访问者状态（isLiked/isCollected，逐请求查互动表）。
 * VO 就是这个"组装结果"的类型 —— 直接把实体丢给前端，等于把表结构变成对外契约。
 * **实体属于存储层，VO 属于展示层，中间的转换就是防腐**。
 *
 * 【同一个 VO 服务多个出口，字段饱满度不同】
 * 发布接口（ContentCommandServiceImpl.publish）返回它：互动状态是初始值
 * （isLiked=false、计数 0）—— 帖子刚出生，什么互动都没有；
 * 详情/列表接口返回它：由 ContentQueryServiceImpl 组装齐全。
 * 一个类承接多种出口是务实选择，代价是"哪些字段此时有值"只能靠约定。
 *
 * 【isLiked/isCollected 是"我"的状态】
 * 这两个字段决定了它**不能进共享缓存**（每个用户看到的不一样），
 * 详情读路径因此拆成"共享快照 + 逐请求补访问者状态"两层 —— 见
 * ContentDetailSnapshot 与 ContentQueryServiceImpl.getContentDetail 的说明。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentVO implements Serializable {

    /**
     * 内容唯一 ID（主键）
     */
    private Long contentId;

    /**
     * 内容类型：1-生活求助 2-专业问答
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
     * 发布用户 ID（关联 tb_user.user_id）
     * 【安全】响应字段，客户端回传也无人消费 —— 作者身份以服务端记录为准。
     */
    private Long publishUserId;

    /**
     * 审核状态：0-待审核 1-已通过 2-已驳回
     * 【为什么审核状态要给前端】"我的帖子"列表要展示"审核中/未通过"角标 ——
     * 这是唯一一个用户能看到非 APPROVED 内容的场景。
     */
    private Integer auditStatus;

    /**
     * 发布时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")// 时区设置为东八区
    private LocalDateTime createTime;

    /**
     * 图片列表
     */
    private List<String> images;

    /**
     * 用户头像
     * 【来自 user 域】作者资料不存 content 表，走 AuthorProfileCache 独立缓存 ——
     * 作者改头像不该失效几百个帖子缓存（粒度问题，见 ContentQueryServiceImpl 的说明）。
     */
    private String avatarUrl;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户部门/专业
     */
    private String quantaDepartment;

    /**
     * 用户届数
     */
    private String quantaBatch;
    /**
     * 点赞数
     * 计数在写路径同事务更新（MySQL 事实源），读时随快照/行带出 —— 不是前端累加出来的。
     */
    private Integer liked;


    /**
     * 点赞高亮
     * "我"是否点过赞（per-visitor），服务端逐请求查询填充；
     * 客户端传什么都会被覆盖 —— 响应 VO 只出不进。
     */    private Boolean isLiked;

    /**
     * 收藏状态
     * 同 isLiked，"我"的状态，不进共享缓存。
     */
    private Boolean isCollected;

    /**
     * 评论数量
     */
        private Integer commentCount;

    /**
     * 收藏数量
     */
    private Integer collectCount;

}
