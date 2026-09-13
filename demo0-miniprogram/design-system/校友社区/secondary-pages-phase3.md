# 二级页面 · 第三阶段增量设计稿

> **范围：** `app.json` 中 13 个非 Tab 页面，按搜索 / 详情 / 表单 / 个人内容 / 用户主页分组。  
> **颗粒度：** 每页 **Phase3 增量** 保留 7 项结构（区块、栅格、组件、色彩、字体图标、交互、衔接点）；完整基线见 `secondary-pages/*.md` 与 `phase2-overview.md`。  
> **共享 token：** [`MASTER-phase3-token-delta.md`](MASTER-phase3-token-delta.md)  
> **评论专项：** [`components/comment-interaction-phase3.md`](components/comment-interaction-phase3.md)、[`components/comment-composer-phase3.md`](components/comment-composer-phase3.md)

---

## 0. 分组共享规则（Phase3）

| 规则 | Phase3 调整 |
|------|----------------|
| 全局 token | 未列出的色仍用 `MASTER.md`；增量仅引用 token-delta |
| 列表统计 | `content-card` 默认 `--color-stat`，已点赞 `--color-stat-active` |
| 内链/展开/重试 | `--color-link`，非 `--color-brand` |
| 收藏激活 | `--color-favorite-active` / `--color-favorite-active-bg` |
| Chip 未选中 | `--color-chip-neutral-bg` + `--color-chip-neutral-text` |
| 专业标签底 | `--color-content-pro-bg` `#EDEAE4`（暖灰褐） |
| 禁止 | 电蓝/紫渐变、emoji 图标、hover scale、单页随意 `#1E5A4C` 作统计色 |

**二阶段不重复：** 搜索热门推荐、楼中楼展开、最佳回答采纳、详情主卡描边、表单去蓝、资产页 `page-list-footer` 等见 `implementation-priority-phase2.md` — 本稿只写 Phase3 **新增**。

---

## 一、搜索组

> 基线稿：[`secondary-pages/search.md`](secondary-pages/search.md)  
> **Phase3 重点：** 热门模块暖色层次、AI 卡片区减少绿色块面。

---

### 1.1 搜索页（`pages/search/index`）

#### 1. 区块（增量）

- 保留：`<search-input-bar>` + `<content-type-tabs>` + 历史卡 + 热门推荐卡。
- Phase3：热门推荐与历史卡**视觉层级**改为「白卡 + border」，模块标题行左侧小图标（火焰/趋势线性，`--color-accent-warm` 仅图标，非整块暖底）。

#### 2. 栅格（增量）

- 热门关键词 chip 区：`gap: 12rpx`；行内 chip 最大宽度 `calc(50% - 6rpx)` 保持换行整齐。
- 热门问题列表项：左图标槽 `40rpx`，与历史 chip 左对齐 `24rpx` 页边。

#### 3. 组件（增量）

- 历史 chip：默认 `--color-chip-neutral-bg` + `1rpx solid var(--color-border)`；hover `--color-chip-neutral-bg` 略深或 `opacity: 0.92`。
- 热门关键词 chip：默认 **neutral**；仅「刚点击过高亮」可用 `--color-brand-muted`（会话级，非默认样式）。
- 清空按钮：字色 `--color-link`；`hover-class` `opacity: 0.85`。

#### 4. 色彩（增量）

| 元素 | Phase3 |
|------|--------|
| 页面底 | `--color-bg`（不变） |
| 模块标题「热门」点缀 | 图标 `--color-accent-warm`，标题字 `--color-text-primary` |
| 历史/热门 chip 默认 | neutral，**不用** `#f5f7fa` 冷灰或大面积 `brand-light` |
| 热门问题序号 | `--color-stat` |

**依据：** **Pro-Max** warm neutral accent；**项目取舍** 缓解搜索页绿块。

#### 5. 字体图标（增量）

- 时钟（历史）：保持 `28rpx` 线框，`--color-text-tertiary`。
- 热门：新增 `24rpx` 线性「趋势」折线图标，线宽 `2.5rpx`，色 `--color-accent-warm`。

#### 6. 交互（增量）

- 点击热门关键词 → 搜索结果（不变）；chip 触摸反馈仅 opacity。
- 推荐加载失败：模块内一行 `--color-link`「重试」，不阻断搜索框。

#### 7. 衔接点

- `pages/search/index.wxss`；复用 `content-type-tabs` Phase3 neutral 未选中。
- 与 `search-result` 关键词 bar 的 `--color-link`「修改」一致。

---

### 1.2 搜索结果页（`pages/search-result/index`）

#### 1. 区块（增量）

- 保留：关键词 bar + tabs + `content-card` 列表 + `ai-summary-card`。
- Phase3：关键词 bar 内「修改搜索」链独立色阶；AI 摘要卡**缩小**绿底面积。

#### 2. 栅格（增量）

- 关键词 bar：`padding: 16rpx 20rpx`；与列表间距 `16rpx`（不变），bar 自身 `margin: 0 24rpx`。

#### 3. 组件（增量）

- `ai-summary-card`：背景改 `--color-chip-neutral-bg` 或白底 + 左边条 `4rpx solid var(--color-accent-warm)`（二选一，全局统一）；标题「AI 摘要」标签字 `--color-text-secondary`。
- 无结果 `state-block`：装饰 neutral；CTA「返回搜索」`--color-cta`。

#### 4. 色彩（增量）

| 元素 | Phase3 |
|------|--------|
| 关键词 | `--color-text-primary` |
| 「修改」链 | `--color-link` |
| 分区 tabs | 同 `content-type-tabs` token |
| 列表卡统计 | `--color-stat` / active |

#### 5. 字体图标（增量）

- 关键词 bar 左侧可选 `24rpx` 搜索放大镜，色 `--color-text-tertiary`。

#### 6. 交互（增量）

- 修改搜索：点击 bar 非卡片区域返回 `search`（不变）；hover 仅 opacity。
- 加载更多 footer：`--color-link` 失败重试。

#### 7. 衔接点

- `pages/search-result/index.*`、`components/ai-summary-card/index.*`。
- AI 详情页头部风格与摘要卡左边条/暖色策略一致。

---

### 1.3 AI 搜索详情页（`pages/search-ai-detail/index`）

#### 1. 区块（增量）

- 保留：头部摘要区 + 正文卡 + 来源列表。
- Phase3：头部**不再**整卡 `brand-light` 铺底；改为白卡 + 顶区 `12rpx` 暖色条或左侧 accent 条。

#### 2. 栅格（增量）

- 头部卡 `padding: 24rpx`；正文卡 `margin-top: 16rpx`；来源区 `margin-top: 20rpx`。

#### 3. 组件（增量）

- 分区标签（「摘要」「来源」）：胶囊 `--color-chip-neutral-bg` + `--color-chip-neutral-text`；当前分区可加字重 600，**不必**绿底。
- 来源项链接：标题 `--color-text-primary`；域名/次要 `--color-link`。

#### 4. 色彩（增量）

| 区域 | Phase3 |
|------|--------|
| 页底 | `--color-bg` |
| 头部强调 | `--color-accent-warm` 细条或图标，非整卡绿 |
| 正文 | `--color-text-primary`，行高 1.55 |
| 引用块 | 左边线 `3rpx solid var(--color-border)`，底 `--color-chip-neutral-bg` |

**依据：** **项目取舍** + phase3-overview「AI 区不用紫/大块绿」。

#### 5. 字体图标（增量）

- 头部 AI 标识：`24rpx` 线性 spark/文档图标，`--color-accent-warm`。

#### 6. 交互（增量）

- 长摘要默认折叠，「展开全文」`--color-link`。
- 来源项点击外链（不变）；触摸 opacity。

#### 7. 衔接点

- `pages/search-ai-detail/index.wxss`；与 `ai-summary-card` 强调策略统一。

---

## 二、详情组

> 基线稿：[`secondary-pages/detail.md`](secondary-pages/detail.md)  
> **Phase3 重点：** 评论长按删除/举报、底栏假输入 + sheet；`detail-pro` 问题下评论流（**已确认 3.2**）+ FAB/底栏/sheet 层级。

**组件稿（必读）：** `comment-interaction-phase3.md`、`comment-composer-phase3.md`。

---

### 2.1 生活详情页（`pages/detail-life/index`）

#### 1. 区块（增量）

- **移除**列表内常驻 `#composer-anchor` + 内嵌 `comment-composer`（仅 sheet 展开时渲染）。
- **改造**底部 `detail-action-bar`：左假输入 + 右点赞/收藏/评论。
- 评论列表区保留「评论」标题 + `comment-list`。

#### 2. 栅格（增量）

- `foot-spacer`：`calc(120rpx + env(safe-area-inset-bottom))`（随底栏高度，与 comment-composer 稿一致）。
- 主卡 `margin: 12rpx 24rpx 0`（不变）。

#### 3. 组件（增量）

- `detail-action-bar`：假输入宽 `flex: 1`，高 `64–72rpx`，圆角 `999rpx`，底 `--color-chip-neutral-bg`，占位「说点什么…」`26–28rpx` `--color-text-tertiary`。
- `comment-composer`：sheet 内 `textarea` + 发送 + `0/500`；回复顶栏「回复 @昵称」。
- `comment-list`：一级/二级 `longpress` → ActionSheet（见 interaction 稿）。

#### 4. 色彩（增量）

| 元素 | Phase3 |
|------|--------|
| 生活 meta chip | 生活暖棕 token（不变） |
| 院系/届 chip | neutral chip |
| 底栏收藏已选 | `--color-favorite-active` |
| 底栏点赞已选 | `--color-stat-active` 或 brand（与 content-card 统一） |
| 评论「回复」链 | `--color-link` |

#### 5. 字体图标（增量）

- 底栏 SVG 图标尺寸 `44–48rpx` 触控区；线图标 `currentColor`。
- 评论点赞数：`--color-stat` / active。

#### 6. 交互（增量）

- 点假输入 / 评论图标 → `openComposer`（**不再** `scrollTo` composer-anchor）。
- 长按评论：举报/删除（权限化）；可选 `wx.vibrateShort`（P2）。
- Sheet 遮罩点击关闭；键盘 `cursor-spacing`、`safe-area`。

#### 7. 衔接点

- `pages/detail-life/index.*`、`components/detail-action-bar/*`、`components/comment-list/*`、`components/comment-composer/*`。
- 删除/举报复用 `comment.service.ts` `deleteComment` / `reportComment`。

---

### 2.2 专业详情页（`pages/detail-pro/index`）

> **产品已确认 3.2：** 问题下内嵌评论流，与 `detail-life` 对齐。

#### 1. 区块（增量）

- 保留：问题主卡 + 回答列表 + FAB「答」+ `detail-action-bar`。
- **新增：** 问题主卡下方 `comment-list` + 根级 `comment-sheet`（无列表内常驻 composer）。
- 底栏：左假输入「说点什么…」+ 右点赞/收藏/评论 → `openComposer`（**不再** scroll/toast）。

#### 2. 栅格（增量）

- z-index：内容 `0` < FAB `40` < 底栏 `50` < sheet `100`（同 `answer-detail`）。
- `foot-spacer` ≥ `calc(112rpx + env(safe-area-inset-bottom))`；FAB `bottom` 高于底栏 `24rpx+`。

#### 3. 组件（增量）

- `comment-list`：`show-more="{{false}}"`；一级/二级仅 `longpress`（**已确认 2.3**）。
- 已采纳标签：success 浅底 + `--color-success` 字。
- 专业类型 chip：`--color-content-pro-bg` + `--color-content-pro-text`。
- 评论 API：`contentId` only → 问题线程 `{contentId}:q`。

#### 4. 色彩（增量）

- 问题卡描边 `var(--color-border)`；评论「回复」`--color-link`。
- 底栏收藏激活 `--color-favorite-active`；假输入 `--color-chip-neutral-bg`。

#### 5. 字体图标（增量）

- FAB「答」：白字 on `--color-cta`（不变）。

#### 6. 交互（增量）

- 采纳按钮：success 语义 + 二次确认（不变）。
- 长按评论：举报/删除（本人）；删除后更新 `detail.commentCount` 与楼中楼 `replyCount`。
- 回答卡「评论 N」/ 进入回答详情：仍讨论**该回答**（`answer-detail`）。

#### 7. 衔接点

- `pages/detail-pro/index.*`；`comment-interaction-phase3.md`、`comment-composer-phase3.md` §7.3。
- 跳转 `publish-answer` / `answer-detail` 不变。

---

### 2.3 回答详情页（`pages/answer-detail/index`）

#### 1. 区块（增量）

- **新增**与 `detail-life` 同款 `detail-action-bar`（收起假输入 + 三操作）。
- **移除**讨论区内嵌常驻 `comment-composer`；评论列表 + sheet composer。
- 保留：问题摘要条 + 当前回答卡 + FAB「答」（若已有）。

#### 2. 栅格（增量）

- 底栏 + FAB 共存：`foot-spacer` 同 detail-life；FAB `bottom` 高于底栏 `24rpx` 以上。

#### 3. 组件（增量）

- 「当前回答」胶囊：底 `--color-brand-light` 字 `--color-brand`（保留，面积小）。
- 底栏、sheet、`comment-list` 同 2.1。

#### 4. 色彩（增量）

- 收藏/点赞/回复链：同 token-delta；已采纳若展示用 `--color-success`。

#### 5. 字体图标（增量）

- 同 detail-life 底栏规范。

#### 6. 交互（增量）

- 与 detail-life 一致：假输入展开、长按评论、回复 @昵称。
- FAB 跳转发布回答（不变）。

#### 7. 衔接点

- `pages/answer-detail/index.*`；**P0** 新增底栏为 Phase3 重点（见 `implementation-priority-phase3.md`）。

---

## 三、表单组

> 基线稿：[`secondary-pages/form.md`](secondary-pages/form.md)  
> **Phase3 重点：** 输入框、计数器、提交钮按新 token；减少表单区浅绿铺底。

---

### 3.1 发布回答页（`pages/publish-answer/index`）

#### 1. 区块（增量）

- 保留：问题摘要卡 + 正文编辑卡 + 底栏发布钮。
- Phase3：摘要卡**缩小**绿底——改为白卡 + 顶 `4rpx` brand 条或左侧 brand 竖条；「正在回答」标签 neutral 胶囊。

#### 2. 栅格（增量）

- 编辑卡 `padding: 24rpx`；底栏 `padding-bottom: calc(16rpx + env(safe-area-inset-bottom))`。

#### 3. 组件（增量）

- `textarea` 聚焦边框 `--color-brand`（不变）；计数默认 tertiary，≥80% `--color-warning`。
- 发布按钮：仅 `--color-cta`；禁用 `#E8EBEF`。

#### 4. 色彩（增量）

| 元素 | Phase3 |
|------|--------|
| 摘要卡背景 | 白底 + border，或 neutral 底 + brand 条 |
| 问题标题 | `--color-text-primary` |
| 元信息 | `--color-text-secondary` |
| 字段错误 | `--color-danger` |

#### 5. 字体图标（增量）

- 摘要区可选 `24rpx` 问答气泡图标，`--color-text-secondary`。

#### 6. 交互（增量）

- 提交中：按钮 loading + 输入禁用（不变）。
- 键盘遮挡：真机验收 `cursor-spacing`（二阶段 P2，Phase3 复验）。

#### 7. 衔接点

- `pages/publish-answer/index.*`；从 `detail-pro` / `answer-detail` 进入参数不变。

---

### 3.2 资料编辑页（`pages/profile-edit/index`）

#### 1. 区块（增量）

- 保留：资料模式 / 认证模式分卡。
- Phase3：认证状态提示条按状态色分离，不用统一灰。

#### 2. 栅格（增量）

- 表单卡间距 `20rpx`；字段 `margin-bottom: 24rpx`（不变）。

#### 3. 组件（增量）

- 输入聚焦：`border-color: var(--color-brand)`（不变）。
- 审核中提示：底 `rgba(42,107,71,0.08)` 字 `--color-success` 或 `--color-text-secondary`（二选一，与 success token 一致）。
- 驳回：底 `rgba(196,61,61,0.08)` 字 `--color-danger`。

#### 4. 色彩（增量）

- 提交钮 `--color-cta`；picker 行箭头 `--color-text-tertiary`。
- 只读字段：`--color-text-disabled` 底 `--color-chip-neutral-bg`。

#### 5. 字体图标（增量）

- 认证模式标题旁 `24rpx` 证件线性图标，`--color-text-secondary`。

#### 6. 交互（增量）

- 字段错误贴近字段（二阶段 P1）；链「查看认证说明」`--color-link`。
- 提交失败恢复可点。

#### 7. 衔接点

- `pages/profile-edit/index.*`；与 `mine` / `user-profile` 认证徽章语义一致。

---

## 四、个人内容组

> 基线稿：[`secondary-pages/my-assets.md`](secondary-pages/my-assets.md)  
> **Phase3 重点：** 列表 tab、空状态、与 `content-card` 统计色统一。

---

### 4.1 我的发布（`pages/my-content/index`）

#### 1. 区块（增量）

- 保留：审核状态 tabs + `content-card` 列表。
- Phase3：tabs 外卡描边；**未选中** chip 改 neutral。

#### 2. 栅格（增量）

- tabs 卡 `padding: 16rpx`；列表 `gap: 20rpx`（不变）。

#### 3. 组件（增量）

- 审核 chip：已通过 `--color-success` 浅底；驳回 `--color-danger` 浅底；审核中 neutral + 字 `--color-text-secondary`。
- 列表卡：统计 `--color-stat`。

#### 4. 色彩（增量）

- 激活筛选 tab：`--color-brand-light` + `--color-brand`。
- 未选中：`--color-chip-neutral-bg`。

#### 5. 字体图标（增量）

- 审核状态可选 `20rpx` 圆点语义色，不单独引入图标库。

#### 6. 交互（增量）

- tab 切换 `hover-class`；列表 footer 失败 `--color-link`。

#### 7. 衔接点

- `pages/my-content/index.*`、`content-card`、`page-list-footer`。

---

### 4.2 我的点赞（`pages/my-liked/index`）

#### 1. 区块（增量）

- 单列表 + 空态；无 tabs。

#### 2. 栅格（增量）

- 与 4.1 列表规范一致。

#### 3. 组件（增量）

- 空态 `state-block`：心形/拇指 CSS 变体 + neutral 装饰底；文案偏社区化；**不强塞**发布 CTA（二阶段策略延续）。

#### 4. 色彩（增量）

- 卡片统计已点赞项 `--color-stat-active`；收藏若展示用 `--color-favorite-active`。

#### 5. 字体图标（增量）

- 空态图标线宽 `2.5rpx`，`--color-text-tertiary`。

#### 6. 交互（增量）

- 下拉刷新保留（若有）；点击进详情。

#### 7. 衔接点

- `pages/my-liked/index.*`。

---

### 4.3 我的收藏（`pages/my-collect/index`）

#### 1. 区块（增量）

- 同 4.2；空态书签图形。

#### 2. 栅格（增量）

- 同 4.1。

#### 3. 组件（增量）

- 列表卡上若有收藏标记：图标 `--color-favorite-active`（**不用** brand 绿）。

#### 4. 色彩（增量）

- 空态 CTA 若有，仅「去逛逛」链 `--color-link` 或弱按钮 secondary，不用玫红。

#### 5. 字体图标（增量）

- 空态书签 `40rpx` 线性图标。

#### 6. 交互（增量）

- 同 4.2。

#### 7. 衔接点

- `pages/my-collect/index.*`；与详情底栏收藏激活色一致。

---

### 4.4 浏览历史（`pages/browse-history/index`）

#### 1. 区块（增量）

- 保留：顶部工具条（清空）+ 列表。

#### 2. 栅格（增量）

- 工具条白卡 `padding: 16rpx 20rpx`；`margin-bottom: 16rpx`。

#### 3. 组件（增量）

- 清空按钮：字 `--color-link`；清空中 `disabled`。
- 列表卡统计 token 化。

#### 4. 色彩（增量）

- 工具条 `1rpx solid var(--color-border)`；背景 `--color-card-bg`。

#### 5. 字体图标（增量）

- 工具条左侧 `24rpx` 时钟图标，`--color-text-tertiary`。

#### 6. 交互（增量）

- 清空确认弹窗（不变）；失败 toast。

#### 7. 衔接点

- `pages/browse-history/index.*`。

---

## 五、用户主页组

> 基线稿：[`secondary-pages/user-profile.md`](secondary-pages/user-profile.md)  
> **Phase3 重点：** 关注按钮、认证标签、统计区用暖/中性色，减少 hero 绿面。

---

### 5.1 用户主页（`pages/user-profile/index`）

#### 1. 区块（增量）

- 保留：hero + TA 的发布列表。
- Phase3：hero 渐变减少 `brand-light`；统计数字默认 `--color-text-primary`，标签 `--color-text-secondary`。

#### 2. 栅格（增量）

- hero `padding: 28rpx 24rpx`；列表区 `margin-top: 20rpx`。

#### 3. 组件（增量）

- 关注按钮：主态 `--color-cta`；已关注 secondary（底 `--color-chip-neutral-bg` 字 `--color-text-secondary` 或描边按钮）。
- 认证徽章：已通过 success 浅底胶囊；未认证 neutral。
- 列表 `content-card`：`showAuthor=false`；统计 `--color-stat`。

#### 4. 色彩（增量）

| 元素 | Phase3 |
|------|--------|
| Hero 渐变 | `--color-accent-warm-light` → `--color-card-bg`（减绿） |
| 统计数字 | 数字 primary，标签 secondary |
| 院系/届 | neutral chip |
| 关注中 | 按钮 disabled + loading |

#### 5. 字体图标（增量）

- 认证徽章旁可选 `20rpx` 勾/盾线性图标，success 色。

#### 6. 交互（增量）

- 关注/取消防重复（不变）；列表 footer 同资产页。
- 自己主页显示「编辑资料」→ `profile-edit`（不变）。

#### 7. 衔接点

- `pages/user-profile/index.*`；与 `mine` 的 `profile-header` token 对齐。

---

## 六、Phase3 二级页验收矩阵

| 页面 | 去绿化 | 评论/底栏 | 暖/中性 accent | token 可追溯 |
|------|--------|-----------|----------------|--------------|
| search | 热门 chip neutral | — | 热门图标 warm | ✓ |
| search-result | AI 卡减绿面 | — | 修改链 link | ✓ |
| search-ai-detail | 头部非整卡绿 | — | accent 条 warm | ✓ |
| detail-life | 院系 chip neutral | 假输入+sheet+长按 | 收藏 warm | ✓ |
| detail-pro | 专业 chip 暖灰底 | **问题下 sheet**（3.2） | success 采纳 + 问题评论 | ✓ |
| answer-detail | 同 life | 新增底栏+sheet | 同 life | ✓ |
| publish-answer | 摘要卡减绿底 | — | warning 计数 | ✓ |
| profile-edit | 状态条分离 | — | link 说明 | ✓ |
| my-content | tabs neutral | — | 审核语义色 | ✓ |
| my-liked / my-collect | 统计/收藏色 | — | 空态 neutral | ✓ |
| browse-history | 工具条 token | — | link 清空 | ✓ |
| user-profile | hero 减绿 | — | 关注 secondary | ✓ |

---

## 七、落地优先级（二级 Phase3 摘要）

| 优先级 | 页面/组件 |
|--------|-----------|
| **P0** | `detail-life`、`answer-detail` + `detail-action-bar` + `comment-list` + `comment-composer`；`app.wxss` token |
| **P1** | 搜索三页、表单两页、资产四页、`user-profile` |
| **P2** | 长按震动、草稿保留、搜索 chip 会话高亮、骨架 variant `assets` |

完整文件级任务见 `implementation-priority-phase3.md`（与一级、组件稿合并）。

---

## 八、需确认（不纳入默认稿）

以下不进上表默认实现，见 `needs-confirm-phase3.md` **未确认**项：评论完整工具栏、复制/置顶/拉黑、全局换主色、首页 Banner 等。**已确认纳入：** 2.3 一级仅长按、3.2 `detail-pro` 问题评论流。

---

## 九、文档索引

| 文件 | 关系 |
|------|------|
| `secondary-pages/search.md` 等 | 二阶段完整 7 项基线 |
| `MASTER-phase3-token-delta.md` | 配色 delta |
| `components/comment-*-phase3.md` | 详情组 P0 交互 |
| `phase3-overview.md` | 阶段目标与 Pro-Max |
| `primary-pages-phase3.md` | 一级增量 |
