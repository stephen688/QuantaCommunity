package com.quanta.demo0.content.service;

import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.content.vo.ContentVO;

/**
 * 内容写入用例。
 *
 * <p>承载内容发布和作者删除事务；查询、互动与推荐由各自领域服务负责。</p>
 *
 * ============================================================
 * 【契约总纲：写路径 = 同步事务 + 异步投影】
 * ============================================================
 * 调用方：内容 Controller（登录用户，身份从 BaseContext 取）。
 * 实现与逐行讲解见 ContentCommandServiceImpl —— 两个方法共享同一条纪律：
 * **同一事务里只做"必须原子"的事（MySQL 事实源 + Outbox 事件登记），
 * 一切容忍延迟的下游（Feed/搜索/向量/推荐池/缓存失效）全部 afterCommit 异步化**。
 * 方法返回即代表事实源已变更，不代表下游已可见——可见性由各投影链路最终一致兜底。
 */
public interface ContentCommandService {

    /**
     * 发布内容（contentType=1 生活帖 / 2 专业区提问），返回 PENDING 状态的视图。
     *
     * 【契约】敏感词/参数校验失败抛 ContentFailedException，不留任何数据；
     * 校验通过则内容、图片关联、（按配置）审核 Outbox 在同一事务落库。
     * 【发布 ≠ 可见】返回的 VO 是 PENDING 态，能否出现在 Feed 由审核状态迁移
     * （approveContent）决定——机审开启走异步审核，机审关闭可按配置自动通过。
     * 【幂等】没有业务幂等键，重复点击会创建多条内容，防重是调用方（前端/网关）的职责。
     */
    ContentVO publish(ContentDTO contentDTO);

    /**
     * 作者删除自己的内容（软删），并联动清理图片/互动明细/评论/回答。
     *
     * 【契约】内容不存在或调用者不是发布者时抛 ContentFailedException；
     * 删除本体与 Feed DELETE、ES 对账 Outbox 在同一事务提交，
     * 推荐流/互动标记/向量库的清理挂在事务提交后（afterCommit）。
     * 删除的"四层清理"分层讲解见 ContentCommandServiceImpl.deleteContent 的方法注释。
     */
    void deleteContent(Long contentId);
}
