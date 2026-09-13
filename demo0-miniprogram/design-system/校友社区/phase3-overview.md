# 校友社区 · 第三阶段设计稿总览

> 本文件为第三阶段（体验抛光 + 评论交互升级 + 配色微调）的总览与 Pro-Max 依据文档。  
> 关联：`MASTER.md`、`MASTER-phase3-token-delta.md`、`phase2-overview.md`、产品方 `美化UI提示词：.md`。

---

## 1. 第三阶段目标

在已完成的一二级设计稿与 token 落地基础上，本阶段**不重做信息架构**，聚焦四项明确目标：

| # | 目标 | 设计稿落点 | 实现重点（后续 `implementation-priority-phase3.md`） |
|---|------|------------|------------------------------------------------------|
| 1 | **配色微调** | `MASTER-phase3-token-delta.md` | 分层用色，缓解「满眼绿」，主 CTA 仍为墨绿 |
| 2 | **二级评论操作** | [`components/comment-interaction-phase3.md`](components/comment-interaction-phase3.md) | `comment-list` 楼中楼 `longpress` → ActionSheet 删除/举报 |
| 3 | **详情评论输入** | [`components/comment-composer-phase3.md`](components/comment-composer-phase3.md) | `detail-action-bar` 假输入 + 收起/展开 sheet |
| 4 | **全局扫尾** | `primary-pages-phase3.md`、`secondary-pages-phase3.md` | 一级 5 页 + 二级 13 页按新 token 增量抛光 |

**约束（与一二阶段一致）：** 不推翻主流程；禁止电蓝渐变、玻璃拟态堆砌、emoji 当图标、无意义光晕；动效 150–300ms、按压不用 `scale`。

**本阶段边界：** 评论完整小红书工具栏（@/表情/相册）、全局换主色、消息/首页新模块等 → `needs-confirm-phase3.md` 未确认项。**已确认纳入：** 2.3 一级仅长按、3.2 `detail-pro` 问题下评论流。

---

## 2. 与 MASTER / 第二阶段的关系

| 层级 | 文件 | 第三阶段用法 |
|------|------|----------------|
| 全局基线 | `MASTER.md` | §1–§2 token、组件状态、反模式**继续有效**；配色仅在 `MASTER-phase3-token-delta.md` 中声明 **delta** |
| 二阶段总览 | `phase2-overview.md` | 二级 13 页分组、Pro-Max 复核、已确认功能**不重复论证** |
| 三阶段 delta | `MASTER-phase3-token-delta.md` | 新增/调整 token 及全局落点清单 |
| 页面增量 | `primary-pages-phase3.md`、`secondary-pages-phase3.md` | 每页只写**第三阶段新增**项，不重复二阶段 P0 已验收项 |

**检索原则（与计划一致）：** 必须先以 `MASTER.md` §1 为基线，再跑本阶段补充检索；每条变更标注 **Pro-Max 依据** / **MASTER 延续** / **项目取舍**。

---

## 3. UI-UX-Pro-Max 补充检索（本轮新执行）

> 执行目录：`demo0-miniprogram` 项目根  
> 命令格式：`python .cursor/skills/ui-ux-pro-max/scripts/search.py "<query>" …`  
> 执行日期：**2026-05-17**

### 3.1 必跑检索矩阵

| 检索 | 命令 / query | 域 | 主要命中 | 采纳 / 不采纳 |
|------|--------------|-----|----------|----------------|
| 设计系统 | `alumni community warm neutral subtle accent education social mobile feed` | `--design-system` | Community/Forum Landing；暖色欢迎；Topic badges 品牌色；Activity indicators **green**；推荐 Vibrant 块面 + 社区紫 `#7C3AED` + Baloo 2 | **部分采纳**：论坛模式、暖底、人像「人味」、活动/成功绿概念 → **MASTER 延续**；**不采纳**紫/玫红/儿童字体/高饱和块面（同 MASTER §1.3） |
| 配色分层 | `warm neutral community accent not monochrome green` | `color` | 社区紫+绿、单色+蓝 CTA、文档灰+蓝链、奢华黑+金点缀 `#CA8A04` | **部分采纳**：**暖中性底 + 非绿点缀**方向 → 强化 `--color-accent-warm`、中性链接；**不采纳**整站紫/电蓝 CTA/粉青创意 Agency 色板 |
| 教育可信色 | `education community warm professional trustworthy` | `color` | 社区紫、B2B 海军蓝、地产青绿 `#0F766E`、招聘蓝+成功绿 | **部分采纳**：「可信 + 成功绿分离」→ `--color-success` 与 `--color-brand` 分工；**不采纳**海军蓝/电蓝为主色 |
| 长按评论 | `long press action sheet comment mobile delete report` | `ux` | 通用：按压反馈、截断、移动键盘、移动优先 | **间接采纳**：按压需即时反馈（`hover-class` / 可选 `wx.vibrateShort`）；删除/举报走微信 `showActionSheet` + 二次确认 → **项目取舍**（小红书式长按，库内无专用条目） |
| 底栏评论输入 | `bottom sheet input keyboard safe area mobile comment` | `ux` | 移动键盘类型、输入可见标签、输入 affordance、焦点态 | **采纳**：`textarea` + `cursor-spacing` / `adjust-position` + `env(safe-area-inset-bottom)`；假输入需明显可点（Input Affordance） |
| 定向补搜 | `action sheet contextual menu mobile touch` | `ux` | 触控目标 ≥44px、间距 ≥8px、触觉反馈适度、手势勿与系统冲突 | **采纳**：回复行 `longpress` 与「回复」「点赞」用 `catchtap` 分离；Sheet 项高度 ≥88rpx |
| 定向补搜 | `sticky bottom bar safe area mobile` | `ux` | 固定栏不遮挡内容；移动键盘；`100vh` 慎用 | **采纳**：详情底栏 `padding-bottom` + safe-area；展开 sheet 时列表预留键盘高度（与二阶段 `form` 组 sticky bar 一致） |

### 3.2 明确不采纳（延续 MASTER §1.3，第三阶段不得回退）

| Pro-Max 推荐 | 不采纳原因 | 对第三阶段的约束 |
|--------------|------------|------------------|
| Primary `#7C3AED` + Background `#FAF5FF` | 社区紫，与浅色校友意象冲突 | 搜索 AI 区、热门 chip 改用暖/中性 token，不用紫块 |
| CTA `#2563EB` / B2B 电蓝 | 产品方禁止电蓝 | 链接、展开、重试改用 `--color-link`（见 token-delta） |
| Primary `#E11D48` + `#FFF1F2` | 娱乐社交玫红 | 收藏激活等不用绿 → 用 `--color-accent-warm`，不用玫红 |
| Baloo 2 + Comic Neue | 儿童教育感 | 字体栈不变 |
| Vibrant & Block-based 全站 | 高饱和模板感 | 仅保留「区块间距、200–300ms 动效」原则 |
| `active:scale-95`（Pro-Max 示例） | 导致布局跳动 | 继续 `opacity` / 背景变化，**禁止 scale** |

### 3.3 现状差距（设计依据，来自代码与产品反馈）

| 议题 | 现状 | 第三阶段设计响应 |
|------|------|------------------|
| 配色 | `--color-brand` / `--color-cta` 均为 `#1E5A4C`；Chip、Tab、链接、成功态大量绿色 | `MASTER-phase3-token-delta.md` 分层：CTA 保留墨绿，次要面改暖/中性 |
| 一级评论 | 原一级「更多」→ ActionSheet | 一级 + 二级均仅 `longpress`（**已确认 2.3** 去掉「更多」） |
| 二级评论 | 回复行仅「回复」「点赞」 | 同上 |
| 评论输入 | `detail-life` / `answer-detail` 内嵌常驻 `comment-composer` | 底栏假输入 + 点击展开 sheet（见 comment-composer 稿） |
| 接口 | `deleteComment` / `reportComment` 已存在 | 交互与列表刷新为主，不需新接口 |

---

## 4. 设计决策溯源（第三阶段）

| 决策 | 来源 | 说明 |
|------|------|------|
| 主品牌墨绿 `#1E5A4C` 不变 | **MASTER 延续** + **项目取舍** | 用户要求「不大改品牌」；Pro-Max 紫/蓝/玫红均不采纳 |
| 新增 `--color-link`、弱化非 CTA 绿面 | **Pro-Max 依据**（warm neutral + accent not monochrome green）+ **token-delta** | 正文内链、展开、重试与 CTA 区分 |
| 收藏已选改用暖色 | **MASTER 延续**（`--color-accent-warm` 已存在）+ **项目取舍** | 避免「收藏 = 又一坨绿」 |
| 一级/二级评论 `longpress` → ActionSheet | **项目取舍**（小红书参照）+ **Pro-Max**（触控反馈、目标尺寸） | 复用 `onCommentMore` / `deleteComment` / `reportComment` |
| 删除仅对自己的评论 | **项目取舍** + 现有 `currentUserId` 比对 | 写入 comment-interaction 稿 |
| 底栏「说点什么…」+ 展开 sheet | **项目取舍**（附图）+ **Pro-Max** | `detail-life`、`detail-pro`（**已确认 3.2**）、`answer-detail` |
| 一级评论仅长按、无「更多」 | **产品确认 2.3** | 一级与二级交互统一 |
| 长按可选 `wx.vibrateShort` | **Pro-Max 依据**（Haptic 适度） | P2，非阻塞 |
| 评论底栏完整工具栏 / 复制 / 置顶 / 换主色 | **需确认** | 见后续 `needs-confirm-phase3.md` |

---

## 5. 页面与组件范围

### 5.1 一级（补强，非重做）

首页、关注、发布、消息、我的 + `custom-tab-bar` — 见 [`primary-pages-phase3.md`](primary-pages-phase3.md)，仅列第三阶段增量（每页 3–5 条）。

### 5.2 二级（13 页，与 `app.json` 一致）

| 分组 | 页面 | Phase3 重点 |
|------|------|-------------|
| 搜索组 | `search`、`search-result`、`search-ai-detail` | 热门模块暖色层次；AI 区减绿块面 |
| 详情组 | `detail-life`、`detail-pro`、`answer-detail` | **评论长按 + 底栏假输入**（P0）；`detail-pro` 问题下评论流（**已确认 3.2**） |
| 表单组 | `publish-answer`、`profile-edit` | 输入/计数/提交钮按新 token |
| 资产组 | `my-content`、`my-liked`、`my-collect`、`browse-history` | 列表 tab、空状态、统计色与 `content-card` 统一 |
| 用户组 | `user-profile` | 关注钮、认证、统计区暖/中性色 |

### 5.3 共享组件（P0 交互）

| 组件 | 路径 | Phase3 变更 |
|------|------|-------------|
| `comment-list` | `miniprogram/components/comment-list` | 回复行 `bindlongpress` |
| `detail-action-bar` | `miniprogram/components/detail-action-bar` | 左假输入 + 右三操作 |
| `comment-composer` | `miniprogram/components/comment-composer` | 仅展开态渲染（sheet 内） |
| 全局 token | `miniprogram/app.wxss` | 见 `MASTER-phase3-token-delta.md` |

---

## 6. 不改动的边界（第三阶段默认稿外）

- 信息架构：首页推荐 / 关注 / 发布 navigateTo / 消息 / 我的 Tab 不变。
- **已确认 3.2：** `detail-pro` 支持**问题下**评论流（`{contentId}:q`）；**回答下**评论仍在 `answer-detail`（`{contentId}:a:{answerId}`）。
- 品牌主色色系不从墨绿改为紫/蓝/玫红。
- 评论发帖不支持图片（无 `imageUrls` 评论能力则不做相册入口）。
- 二阶段已验收项（搜索热门、楼中楼展开、固定底栏表单等）不重复写进一级增量稿。

---

## 7. 后续交付物索引

| 文件 | 状态 |
|------|------|
| `phase3-overview.md`（本文件） | ✅ Pro-Max 补充检索 + 阶段目标 |
| `MASTER-phase3-token-delta.md` | ✅ 配色微调 token |
| `components/comment-interaction-phase3.md` | 待产出 |
| `components/comment-composer-phase3.md` | 待产出 |
| `primary-pages-phase3.md` | 已产出 |
| `secondary-pages-phase3.md` | 已产出 |
| `needs-confirm-phase3.md` | ✅ 大改待确认清单 |
| `implementation-priority-phase3.md` | ✅ P0/P1/P2 落地与验收 |

---

## 附录 A：本轮 `--design-system` 原始输出摘要

<details>
<summary>2026-05-17 — alumni community warm neutral …</summary>

- **Pattern:** Community/Forum Landing — 活跃成员、内容预览、低门槛参与  
- **Color Strategy:** Warm, welcoming；Member photos；Topic badges in brand colors；**Activity indicators green**  
- **Style（未采纳）:** Vibrant & Block-based — Bold, duotone, high contrast  
- **Colors（未采纳）:** Primary `#7C3AED`, CTA `#22C55E`, Background `#FAF5FF`  
- **Typography（未采纳）:** Baloo 2 + Comic Neue  
- **Effects（采纳概念）:** 区块间距 48px+、hover 色移、**200–300ms**  
- **Avoid:** Heavy skeuomorphism；a11y ignored；emoji 当图标  

</details>

<details>
<summary>color 域 — warm neutral community accent not monochrome green（节选）</summary>

| Product Type | Primary | CTA | Notes | 处理 |
|--------------|---------|-----|-------|------|
| Membership/Community | `#7C3AED` | `#22C55E` | 紫+绿 | 不采纳紫；绿仅作 success 语义 |
| Knowledge Base | `#475569` | `#2563EB` | 灰+蓝链 | 采纳「中性+独立链接色」思路 → `--color-link` |
| E-commerce Luxury | `#1C1917` | `#CA8A04` | 黑+金点缀 | 采纳「暖点缀」思路 → 强化 `--color-accent-warm` |
| Portfolio/Personal | `#18181B` | `#2563EB` | 单色+蓝 | 不采纳电蓝 CTA |

</details>

<details>
<summary>ux 域 — 底栏 / 触控（节选）</summary>

- **Input Affordance:** 输入区需可见边框/背景 — 假输入槽 `#F0EDE7` / `var(--color-bg)`  
- **Mobile Keyboards:** 评论 `textarea` 使用文本键盘；字数计数可见  
- **Touch Target Size:** 底栏图标与假输入最小触控区 ≥ 88rpx 高  
- **Haptic Feedback:** 长按出 Sheet 可选短震，勿全站滥用  
- **Sticky Navigation:** 固定底栏需 `padding-bottom` 补偿，避免遮挡末条评论  

</details>
