# 校友社区 · 第二阶段设计稿总览

> 本文件为第二阶段（一级补强 + 二级页高颗粒度设计稿）的总览与依据文档。  
> 关联：`MASTER.md`、`implementation-priority.md`、`needs-confirm.md`、产品方 `美化UI提示词：.md`。

---

## 1. 第二阶段目标

在已完成的一级五页设计系统与 token 落地基础上：

1. **一级页补强** — 图标、状态细节、骨架屏、列表尾部、空状态等（不重复 MASTER 已定的结构与 token）。
2. **二级页统一** — 按 `app.json` 中 13 个非 Tab 页面，输出与一级页同颗粒度的美化设计稿。
3. **大改隔离** — 涉及新功能、流程或信息架构变更的建议进入 `needs-confirm-phase2.md`（后续产出）。

**约束（与第一轮一致）：** 不推翻现有功能与主流程；禁止电蓝渐变、玻璃拟态、emoji 图标；动效 150–300ms、按压不用 scale。

---

## 2. UI-UX-Pro-Max 依据复核

### 2.1 既有检索（来自 `MASTER.md` §1，继续有效）

| 检索 | 关键词 / 命令 | 主要命中 | 第二阶段沿用方式 |
|------|---------------|----------|------------------|
| 设计系统 | `alumni community education social warm trustworthy mobile app` | Community/Forum Landing；暖色欢迎；活动绿 | 二级页仍按「论坛/社区流 + 低门槛参与」组织 CTA |
| 补充 | `alumni university community warm professional trustworthy` | 学术气质字体（Garamond 系） | 小程序仍用系统字体栈，不引入 Web Font |
| color | `education community social feed mobile` | 社区紫 / 社交玫红 | **继续不采纳**；沿用 MASTER 墨绿 + 暖纸底 |
| typography | `professional warm readable mobile` | Corporate Trust；Noto Sans SC 方向 | 二级详情/表单沿用 `--font-display` ~ `--font-caption` 阶梯 |
| ux | `loading pull refresh skeleton empty state` | 空状态需引导动作；导航当前态；慎用无意义下拉刷新 | 二级列表/详情/表单全面覆盖空/错/加载；非信息流页禁用下拉刷新 |

### 2.2 已采纳原则（MASTER §1.2，二级页直接继承）

| 原则 | 二级页落地要点 |
|------|----------------|
| Community/Forum 产品模式 | 搜索/详情/用户主页强调「人」与「内容预览」，主 CTA 单一明确 |
| 暖底 + 活动/成功绿 | 认证、关注成功、提交成功用 `--color-success`，不用装饰光晕 |
| 动效 150–300ms | 搜索历史删除、Tab 切换、按钮 `hover-class` 与一级一致 |
| 反模式：无 emoji 图标、对比度、非 instant 切态 | 二级页图标延续 CSS 几何或统一线性风格 |
| 产品方禁止项 | AI 摘要区不用电蓝/紫渐变；AI 页用 `--color-brand-light` 底区分即可 |

### 2.3 明确不采纳（MASTER §1.3，第二阶段不得回退）

| 检索推荐 | 不采纳原因 | 对二级页的约束 |
|----------|------------|----------------|
| `#E11D48` 玫红 + `#FFF1F2` 底 | 偏娱乐社交 | 搜索无结果、空收藏等引导不用玫红主色 |
| Baloo 2 / Comic Neue | 儿童教育感 | 详情长文、AI 摘要保持 `--font-body` 可读行高 |
| Vibrant 高饱和块面 | 模板感 | 详情互动栏、用户主页统计区克制描边，少色块墙 |
| `#7C3AED` 社区紫 + OLED Dark | 与浅色校友意象不符 | AI 详情、专业详情不用紫色作为主强调 |
| 蓝紫发布渐变 | 电蓝 AI 套路 | 发布回答、资料编辑提交钮仅用 `--color-cta` 纯色 |

### 2.4 品牌与 Design Token（MASTER §2，二级页默认引用）

二级页**不另起色板**，默认使用 `MASTER.md` 中已定义 token。页面级仅在 `design-system/校友社区/secondary-pages/*.md` 中说明区块用法差异。

**实现状态说明：** `app.json` 的 `window.backgroundColor` 与 TabBar `selectedColor` 已为 `#F7F5F0` / `#1E5A4C`，与 MASTER 一致；部分二级页 WXSS 可能仍残留旧蓝，属 P1 统一范畴，不在本复核中改代码。

---

## 3. 二级页补充检索（本轮新执行）

> 命令格式：`python …/ui-ux-pro-max/scripts/search.py "<query>" --domain ux|color -n 5`  
> 执行日期：2026-05-16

### 3.1 检索矩阵

| 页面组 | 检索 query | 域 | 高相关命中 | 采纳 / 取舍 |
|--------|------------|-----|------------|-------------|
| **搜索组** | `mobile search history results empty state` | ux | 无结果需建议而非死胡同；空状态需说明+动作 | **采纳**：`search-result` 无结果用 `state-block` + 改写关键词/回首页；历史空态单一 CTA |
| **搜索组** | 产品确认 `needs-confirm-phase2.md` 2.1 | - | 热门搜索 / 热门校友推荐可采纳 | **采纳**：搜索页新增轻量热门推荐，优先关键词 + 热门问题；热门校友有数据时展示 |
| **搜索组** | `AI summary answer chat feedback` | ux | AI 需要反馈回路（赞/踩/重新生成） | **不纳入默认稿**：无对应接口与业务；若要做进 `needs-confirm-phase2` |
| **详情组** | `article detail reading mobile typography hierarchy` | ux | 字号阶梯一致；正文行高 1.5–1.75 | **采纳**：标题 `--font-title`，正文 `--font-body` 行高 1.55，元信息 `--font-caption` |
| **详情组** | 产品确认 `needs-confirm-phase2.md` 3.3 | - | 专业详情回答采纳 / 最佳回答流程可采纳 | **采纳**：问题作者可采纳回答，详情列表与回答详情同步最佳回答状态 |
| **详情组** | 产品确认 `needs-confirm-phase2.md` 3.1 | - | 评论分层 / 楼中楼可采纳 | **采纳**：后端不改，前端展开时调用回复列表并挂载到一级评论下 |
| **详情组** | `comment thread discussion mobile` | ux | 移动键盘类型；返回键行为 | **采纳**：回答/评论输入区预留底部安全区；楼中楼展开保持行内 loading 与失败重试 |
| **表单组** | `mobile form validation error feedback submit` | ux | 提交需 loading→成功/失败；错误靠近字段；错误需可恢复 | **采纳**：`profile-edit` / `publish-answer` 字段旁 `--color-danger`；底栏提交 loading/disabled |
| **表单组** | `sticky bottom bar safe area mobile` | ux | 固定底栏不得遮挡内容 | **采纳**：与一级发布页固定底栏同一规则（padding-bottom + safe-area） |
| **个人内容组** | `content list saved history empty loading` | ux | 空状态引导；懒加载；长文截断；避免布局跳动 | **采纳**：四页复用 `content-card`；列表 `lazy-load`；骨架贴近真实行高 |
| **用户身份组** | `user profile social trust follow` | ux | 动效 reduced-motion；blur 校验 | **采纳**：认证徽章 token 化；关注态文案+颜色双表达；动效遵循 MASTER |
| **全局状态** | `error retry skeleton loading state` | ux | 错误需恢复路径；勿仅红色边框 | **采纳**：沿用 `state-block` + `bind:action` 重试；详情/列表错误统一文案结构 |
| **色彩复核** | `education community social feed mobile` | color | 社区紫 `#7C3AED`、社交玫红 | **再次确认不采纳**；success 绿 `#22C55E` 概念映射到 `--color-success` |

### 3.2 与 MASTER 重复、无需再检索的项

以下在一级稿与 `MASTER.md` 已覆盖，二级页**直接引用**，不重复跑 Pro-Max：

- 下拉刷新：仅信息流式列表（搜索历史加载、个人内容列表若已启用则保持；详情/表单/搜索输入页**不新增**）
- 骨架屏：`feed-skeleton` 已有 variant，二级仅需指定 variant（如 `home` / 通知型）
- 触摸反馈：`hover-class` 透明度 0.85，禁止 scale
- 空状态：`state-block` 三件套（标题 + 说明 + 单一 CTA）

### 3.3 建议追加检索（设计稿编写前可选）

若某二级页设计卡住，可按下表**定向补搜**（非阻塞本轮）：

| 场景 | 建议 query | 域 |
|------|------------|-----|
| 搜索结果高亮 | `search highlight keyword snippet` | ux |
| 长图详情 | `image gallery mobile swipe aspect ratio` | ux |
| 表单分节 | `multi-section profile form mobile` | ux |
| 筛选 Tab | `filter tabs underline mobile` | ux |
| 微信栈实现 | `list performance virtual scroll` | 可试 `--stack`（无微信专用栈时参考 html-tailwind 布局原则） |

---

## 4. 设计决策溯源（MASTER / 补充检索 / 落地取舍）

| 决策 | 来源 | 说明 |
|------|------|------|
| 墨绿 `#1E5A4C` 主色、暖纸底 `#F7F5F0` | **MASTER**（Pro-Max 取舍后定稿） | 二级页全局背景、CTA、链接强调 |
| 生活/专业标签双色 | **MASTER** + **落地取舍** | 保留业务语义，弱化饱和 |
| 空状态 `state-block` 结构 | **MASTER**（Pro-Max empty state） | 搜索历史、我的发布/点赞/收藏/历史均已使用 |
| 无结果「建议搜索词」文案 | **补充检索** §3.1 搜索组 | 结果页空态优于纯「0 条结果」 |
| 搜索页热门推荐 | **产品确认** `needs-confirm-phase2.md` 2.1 + **落地取舍** | 只做轻量推荐，不做输入联想或高级筛选 |
| 详情正文字号阶梯 | **补充检索** §3.1 详情组 + **MASTER** typography token | 禁止详情页随意加大字号破坏阶梯 |
| 专业问答最佳回答采纳 | **产品确认** `needs-confirm-phase2.md` 3.3 + **落地取舍** | 问题作者可采纳回答；不扩展奖励机制 |
| 详情页评论分层/楼中楼 | **产品确认** `needs-confirm-phase2.md` 3.1 + **落地取舍** | 后端代码不改；前端在展开时多次调用回复列表接口，承担拼装和状态管理 |
| 发布回答/资料编辑固定底栏 | **MASTER** §3.4 + **补充检索** sticky bar + **落地取舍** | 与一级 `publish` 已确认方案对齐 |
| AI 详情无赞踩/重新生成 | **补充检索** 命中 + **落地取舍** | 无接口则不做默认稿；避免伪 AI 控件 |
| 搜索页 autocomplete 下拉 | **补充检索** 命中 + **落地取舍** | 小程序已有 `search-input-bar` + 历史 chip；不新增未实现联想接口 |
| 用户主页私信入口 | **落地取舍** → **需确认** | 无路由则不画进默认稿 |
| 二级页下拉刷新 | **MASTER** + **落地取舍** | 个人内容列表若已有则保留；详情/表单/搜索页默认关闭 |
| 骨架 variant 混用 `home` | **落地取舍** | 搜索历史/详情加载暂用 `home` variant，P1 可拆 `detail`/`search` variant |

---

## 5. 二级页面范围与分组（设计稿产出顺序）

以 `miniprogram/app.json` 为准（13 页，不含 Tab 一级页）：

| 分组 | 页面路径 | 后续设计稿文件 |
|------|----------|----------------|
| 搜索组 | `pages/search`、`pages/search-result`、`pages/search-ai-detail` | `secondary-pages/search.md` |
| 内容详情组 | `pages/detail-life`、`pages/detail-pro`、`pages/answer-detail` | `secondary-pages/detail.md` |
| 发布/编辑组 | `pages/publish-answer`、`pages/profile-edit` | `secondary-pages/form.md` |
| 个人内容组 | `pages/my-content`、`pages/my-liked`、`pages/my-collect`、`pages/browse-history` | `secondary-pages/my-assets.md` |
| 用户身份组 | `pages/user-profile` | `secondary-pages/user-profile.md` |

**共享组件（二级优先复用）：** `content-card`、`content-type-tabs`、`state-block`、`feed-skeleton`、`search-input-bar`、`profile-header`（用户主页）。

---

## 6. 第一阶段已确认、第二阶段继续沿用

来自 `needs-confirm.md` §0，**不重复讨论**，二级页仅引用 token/交互节奏：

- 首页完整搜索框入口 → 搜索组承接视觉
- 发布页固定底栏 → `publish-answer` 同规范
- 消息全部已读 → 与二级无关
- 我的资料完整度 → `profile-edit` 为跳转目标

---

## 7. 后续交付物索引

| 文件 | 状态 |
|------|------|
| `phase2-overview.md`（本文件） | ✅ UI-UX-Pro-Max 复核与二级检索方向 |
| `primary-pages-polish.md` | ✅ 一级五页与 TabBar 缺失/可优化项盘点完成 |
| `secondary-pages/search.md` | ✅ 搜索组设计稿完成 |
| `secondary-pages/detail.md` | ✅ 内容详情组设计稿完成 |
| `secondary-pages/form.md` | ✅ 发布/编辑组设计稿完成 |
| `secondary-pages/my-assets.md` | ✅ 个人内容组设计稿完成 |
| `secondary-pages/user-profile.md` | ✅ 用户身份组设计稿完成 |
| `needs-confirm-phase2.md` | ✅ 第二阶段需确认大改建议完成 |
| `implementation-priority-phase2.md` | ✅ 第二阶段前端落地优先级完成 |

---

## 附录 A：本轮 Pro-Max 原始命中摘要

<details>
<summary>补充检索 ux 域 — 搜索无结果</summary>

- **Do:** Show "No results" with suggestions（如「试试搜索 xxx」）
- **Don't:** Blank screen or "0 results" only
- **Severity:** Medium

</details>

<details>
<summary>补充检索 ux 域 — 表单提交</summary>

- **Do:** Loading → success/error；错误靠近字段；提供重试
- **Don't:** 提交无反馈；仅红色边框无文案
- **Severity:** High

</details>

<details>
<summary>补充检索 color 域 — 社区色板（未采纳）</summary>

- Membership/Community: Primary `#7C3AED`, CTA `#22C55E`, Background `#FAF5FF`
- Social Media: Primary `#E11D48`, CTA `#2563EB`, Background `#FFF1F2`
- **处理：** 仅采纳「加入/成功用绿」概念 → `--color-success: #2D7A4F`

</details>
