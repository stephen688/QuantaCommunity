package com.quanta.demo0.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 发布内容入参（POST /content/publish 的 body）。
 *
 * ============================================================
 * 【这个 DTO 上没有的三个字段，比有的字段更值得讲】
 * ============================================================
 * 1. 没有 publishUserId —— 发布者从 JWT 解出（BaseContext），绝不从 body 拿。
 *    否则客户端改个数字就能替任何人发帖（水平越权）。**能从凭证推导的，不让客户端声明**。
 * 2. 没有 auditStatus/isDeleted 等状态字段 —— 新帖一律 PENDING，状态机的初始态
 *    和流转都由服务端管（见 ContentCommandServiceImpl.publish）；
 *    客户端只能提供"素材"，不能提供"状态"，否则能直接提交"已审核通过"的帖子。
 * 3. 没有校验注解（@NotBlank/@Size）—— 长度/类型校验手写在 CommandService 里。
 *    不理想，但保证 Bot/管理端等其它入口复用同一条写路径时无法绕过规则。
 *
 * 【与 ContentVO 的方向相反】DTO 是"客户端 → 服务端"的写形状，
 * 只带服务端需要消费的字段；返回给前端的是 ContentVO（多了作者信息/计数/互动状态）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentDTO implements Serializable {

    /**
     * 内容类型：1-专业问答 2-生活求助
     * 【坑】不传或传其它值会在 Service 被拒 —— 它同时决定帖子进哪个分类池
     * （生活/专业），不是可有可无的展示标签。
     */
    private Integer contentType;

    /**
     * 问题标题（限制 50 字以内）
     * 【安全】发布前先过敏感词检查（SensitiveWordChecker），命中直接拒绝；
     * 错配（超 50 字）在 Service 报业务异常，而不是数据库截断静默吞掉。
     */
    private String title;

    /**
     * 问题描述（限制 500 字以内）
     * 同样过敏感词 + 长度校验；正文是阿里云内容安全机审的主要对象
     * （quanta.moderation 配置，见 ContentCommandServiceImpl 的审核策略说明）。
     */
    private String content;

    /**
     * 图片列表（最多 5 张）
     * 存的是客户端先上传 OSS 拿到的 URL，不是图片本体 —— 大文件走引用不走值。
     * 服务端只做"去空 + 最多 5 张"校验；URL 是否可信由上传接口保证，
     * 内容是否违规由机审的图片审核通道异步把关。
     */
    private List<String>images;

}
