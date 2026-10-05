package com.quanta.demo0.content.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端 - 内容分页查询入参
 *
 * 【GET 请求的 body 不存在：筛选条件走 Query Param 绑定】
 * 方法参数上没有 @RequestBody，Spring MVC 按字段名把 ?pageNum=1&auditStatus=0
 * 自动装进这个对象 —— GET 语义是"安全可缓存"，查询条件放 URL 才能被收藏/分享/记日志。
 *
 * 【四个字段全是"可选 + 有默认"的形状】
 * 分页有默认值，两个筛选不传 = 查全部 —— 运营控制台的典型入参设计：
 * 打开页面第一眼要看到的是"全量列表"，筛选是逐步收窄的动作。
 * 注意它和用户侧的筛选入参不同类：管理端查询的是**全状态**内容
 * （含待审核/已驳回），用户侧永远只见 APPROVED —— 同一张表，两套可见性语义。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContentAdminQueryDTO {
    /**
     * 页码，默认 1
     * 【坑】没有下限/上限校验，直接透传给 PageHelper —— 调用方可信
     * （已过 CONTENT_READ_ADMIN 权限门槛），参数收紧强度与调用方信任度匹配
     * （对比 Bot 同步接口在 Service 里 clamp，见 AdminContentController.page 的说明）。
     */
    @Builder.Default
    private Integer pageNum = 1;

    /**
     * 每页数量，默认 10
     */
    @Builder.Default
    private Integer pageSize = 10;

    /**
     * 审核状态筛选（可选）：0-待审核 1-已通过 2-已驳回
     * 管理员最常用的视图就是"待审核队列"（auditStatus=0）——
     * 人工审核的工作清单从这里来。
     */
    private Integer auditStatus;

    /**
     * 内容类型筛选（可选）：1-生活求助 2-专业问答
     */
    private Integer contentType;
}