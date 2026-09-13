# 内容详情组设计稿

> **分组：生活详情页 · 专业详情页 · 回答详情页**  
> 关联：`MASTER.md`、`phase2-overview.md §3.1 详情组补充检索`。  
> 技术栈：微信小程序，样式 `rpx` + CSS 变量，与 `app.wxss` 对齐。

---

## 一、分组共享规则

| 规则 | 说明 |
|------|------|
| 背景色 | 页面 `var(--color-bg)`；内容卡 `var(--color-card-bg)` + `1rpx solid var(--color-border)` |
| 标题阶梯 | 内容标题 `var(--font-title)` `34rpx / 600 / 1.35`；正文 `var(--font-body)` `28rpx / 400 / 1.55`；元信息/时间 `var(--font-caption)` `24rpx / 400 / 1.45` |
| 作者行 | 统一使用 `<author-row>`；clickable 跳用户主页 |
| 固定底栏 | `<detail-action-bar fixed>`；背景 `var(--color-surface-elevated)` `#FFFFFF`；顶边 `var(--color-border)` |
| 底部留白 | `height: calc(120rpx + env(safe-area-inset-bottom))`；保证固定栏不遮挡正文 |
| 互动按钮激活色 | 点赞激活 `var(--color-brand)`；收藏激活 `var(--color-accent-warm)` `#B45309` |
| 错误/删除状态 | `<state-block>` + 单一 CTA |
| 加载状态 | `<feed-skeleton variant="home" count="1">`；等待首次数据时全屏 |
| 评论/回答空状态 | `<state-block type="empty">` + 说明文案；**不显示为空白** |
| 楼中楼评论 | 已确认采用后端不改方案：一级评论先加载，点击展开时前端调用回复列表并挂载到对应评论下 |
| 触摸反馈 | `hover-class` 降透明度 `0.85`，禁止 `scale` |
| 图片 | `mode="aspectFill"`；列表 `lazy-load`；点击 `wx.previewImage` |
| 生活/专业标签 | 生活：底 `#F0EBE3` 字 `#6B5B4F`；专业：底 `#E8F2EF` 字 `#1E5A4C` |

---

## 二、生活详情页（`pages/detail-life/index`）

### 2.1 页面目标

完整呈现一条生活类帖子内容（图文 + 评论），支持点赞、收藏、评论交互。

### 2.2 信息区块

```
┌────────────────────────────────────┐
│  内容主卡                          │
│    工具栏（flex-fill + 更多）      │
│    <author-row>                    │
│    标题（可选）                    │
│    图片组（可选）                  │
│    正文                            │
│    元信息 chips：生活/院系/届      │
├────────────────────────────────────┤
│  "评论" 区块标题                   │
│  <comment-list>                    │
│  <comment-composer>                │
│  foot-spacer                       │
├────────────────────────────────────┤
│  <detail-action-bar fixed>         │
└────────────────────────────────────┘
```

### 2.3 内容主卡

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-card-bg)` |
| 圆角 | `var(--radius-card)` `16rpx` |
| border | `1rpx solid var(--color-border)` |
| padding | `24rpx` |
| margin | `12rpx 24rpx 0` |

### 2.4 工具栏（toolbar）

| 属性 | 规格 |
|------|------|
| 布局 | `display: flex; justify-content: space-between; align-items: center` |
| 左侧 fill | 占满剩余空间（已有 `detail-page__toolbar-fill`） |
| 「更多」 | `font-size: 26rpx`；`var(--color-text-tertiary)`；触摸 `opacity: 0.72` |

### 2.5 标题与正文

| 层级 | 规格 |
|------|------|
| 标题 `.detail-page__title` | `font-size: var(--font-title)` `34rpx`；`font-weight: 600`；`var(--color-text-primary)`；`line-height: 1.35`；`margin: 16rpx 0 12rpx` |
| 正文 `.detail-page__content` | `font-size: var(--font-body)` `28rpx`；`line-height: 1.55`；`var(--color-text-primary)`；`user-select`；`margin-top: 12rpx` |

### 2.6 图片组

| 属性 | 规格 |
|------|------|
| 布局 | `display: flex; flex-wrap: wrap; gap: 8rpx`；单图满宽；多图 3 列等宽 |
| 图片 | `border-radius: 8rpx`；`aspect-ratio: 1`（正方形）；`mode: aspectFill` |
| 占位底色 | `background: #F0EDE7`（暖灰，替换蓝灰） |

### 2.7 元信息区（meta chips）

| 属性 | 规格 |
|------|------|
| 布局 | `display: flex; flex-wrap: wrap; gap: 8rpx; margin-top: 16rpx` |
| 每个 chip | `font-size: var(--font-caption)` `24rpx`；`padding: 4rpx 14rpx`；`border-radius: 999rpx` |
| 生活 chip | 底 `#F0EBE3`；字 `#6B5B4F` |
| 院系/届 chip | 底 `var(--color-bg)`；字 `var(--color-text-secondary)`；`border: 1rpx solid var(--color-border)` |

### 2.8 评论区块

| 属性 | 规格 |
|------|------|
| 区块标题「评论」 | `font-size: 26rpx`；`font-weight: 600`；`var(--color-text-primary)`；`margin: 20rpx 24rpx 8rpx` |
| `<comment-list>` | 复用组件，颜色 token 化（见 MASTER §3）；评论条目间分割线 `var(--color-divider)`；支持点击展开二级回复 |
| `<comment-composer>` | 底部输入区；聚焦后键盘上移（已有 `cursor-spacing`）；`padding-bottom: env(safe-area-inset-bottom)` |

### 2.9 评论楼中楼（已确认新增）

| 状态 | 处理 |
|------|------|
| 默认 | 一级评论平铺展示；若 `replyCount > 0` 或已有 `replyList`，评论底部显示「展开 N 条回复」 |
| 展开中 | 当前评论显示行内 loading「加载回复中…」，按钮 disabled，避免重复请求 |
| 展开成功 | 前端把 `getReplyList(parentCommentId, contentId)` 返回结果插入到该评论 `replies/replyList` 下方，缩进 `48rpx` |
| 展开失败 | 显示「回复加载失败，点此重试」，颜色 `var(--color-danger)` + 重试链接 `var(--color-brand)` |
| 已展开 | 展示「收起回复」；收起时只折叠前端展示，不清空已加载回复 |
| 回复二级评论 | 点击二级回复时仍复用现有回复输入，`replyCommentId` 指向被回复评论，`parentCommentId` 指向一级评论 |

**后端边界：** 不改后端代码；前端先拉一级评论，展开时再多次调用现有回复列表接口。该方案会增加前端状态和请求次数，但能保持接口稳定。

### 2.10 固定底栏 `<detail-action-bar>`

| 元素 | 规格 |
|------|------|
| 整体 | `position: fixed; bottom: 0; left: 0; right: 0`；高 `96rpx + env(safe-area-inset-bottom)`；背景 `var(--color-surface-elevated)`；`border-top: 1rpx solid var(--color-border)` |
| 评论入口 | 最左，低键盘感输入框样式；`border-radius: 999rpx`；背景 `var(--color-bg)` |
| 点赞 | 图标 + 计数；激活色 `var(--color-brand)` |
| 收藏 | 图标 + 计数；激活色 `var(--color-accent-warm)` |
| 评论数 | 纯数字展示，`var(--color-text-secondary)` |

### 2.11 状态覆盖

| 状态 | 处理 |
|------|------|
| 加载中（无数据） | 全屏 `<feed-skeleton variant="home" count="1">` |
| 加载失败 | `<state-block type="network|server">` + 重试 |
| 内容已删除 | `<state-block type="empty" title="内容已删除" actionText="回首页">` |
| invalidId | `<state-block type="invalidData">` + 返回 |
| 评论加载失败 | 评论区内 `<state-block>` + 重试 |
| 评论为空 | 评论区无明显空状态（可与「来写第一条评论」文案引导） |
| 回复展开中 | 当前评论内行内 loading，不影响其他评论 |
| 回复加载失败 | 当前评论内重试，不提升为整页错误 |
| 图片加载失败 | 显示暖灰占位色 `#F0EDE7` |

### 2.12 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 内容卡加 border | `pages/detail-life/index.wxss` `.detail-page__card` | 加 `border: 1rpx solid var(--color-border)` |
| 图片占位色 | `.detail-page__img` | 加 `background: #F0EDE7` |
| 元信息 chip 圆角 | `.detail-page__meta-text` | 改为胶囊圆角，应用生活底色 |
| 底部留白 | `.detail-page__foot-spacer` | 确认 `height: calc(120rpx + env(safe-area-inset-bottom))` |
| 楼中楼展开 | `components/comment-list/index.*`、`pages/detail-life/index.ts` | 使用 `loadreplies` 事件，展开时调用现有 `getReplyList` 并更新对应一级评论 |

---

## 三、专业详情页（`pages/detail-pro/index`）

### 3.1 页面目标

展示一条专业问答内容，含问题描述和回答列表；支持点赞收藏、发布回答入口（FAB），并按已确认方案支持问题作者采纳最佳回答。

### 3.2 信息区块

```
┌────────────────────────────────────┐
│  问题主卡                          │
│    标题（大字，必填）               │
│    <author-row>                    │
│    标签行：专业/院系/届 + 更多       │
│    问题正文（可选）                 │
│    图片组（可选）                   │
├────────────────────────────────────┤
│  "回答 · N 条" 区块标题             │
│  回答卡列表                        │
│    问题作者可采纳 / 已采纳状态       │
│  foot-spacer                       │
│  发布回答 FAB                      │
├────────────────────────────────────┤
│  <detail-action-bar fixed>         │
└────────────────────────────────────┘
```

### 3.3 问题主卡（与生活详情差异）

| 差异项 | 规格 |
|--------|------|
| 标题位置 | 在 `<author-row>` 之前（醒目问题优先） |
| 标签行 `.detail-page__subhead` | 标签组 + 更多；标签：专业底 `#E8F2EF` 字 `#1E5A4C`；圆角 `999rpx`；`padding: 4rpx 14rpx` |
| 无图片占位 | 专业问答通常无图，主卡紧凑 |

### 3.4 回答卡 `.detail-page__ans`

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-card-bg)` |
| border | `1rpx solid var(--color-border)` |
| 圆角 | `16rpx` |
| padding | `20rpx 24rpx` |
| 间距 | `margin-bottom: 16rpx` |
| 头部 | `<author-row>` + 「已采纳」徽章 |
| 已采纳徽章 | 背景 `var(--color-success)` `#2D7A4F`；字 `#FFFFFF`；`font-size: 22rpx`；`padding: 4rpx 10rpx`；`border-radius: 999rpx` |
| 届信息 `.detail-page__ans-batch` | `font-size: var(--font-caption)` `24rpx`；`var(--color-text-tertiary)` |
| 正文 | `font-size: var(--font-body)` `28rpx`；`line-height: 1.55`；支持 `user-select` |
| 底部统计 | 时间 `var(--color-text-tertiary)`；「评论」「赞 N」`var(--color-text-secondary)`；赞激活色 `var(--color-brand)` |
| 点击进入详情 | `.detail-page__ans-entry-text` 颜色 `var(--color-brand)` |

### 3.5 采纳最佳回答（已确认新增）

| 状态 | 规格 |
|------|------|
| 可采纳 | 仅问题作者可见；按钮文案「采纳」或「设为最佳」；Ghost 样式，字 `var(--color-success)`，边框 `rgba(45,122,79,0.28)` |
| 已采纳 | 回答卡顶部显示「最佳回答」或「已采纳」success 胶囊；该回答排序置顶（若接口返回已排序则前端不重排） |
| 采纳中 | 按钮 disabled，文案「处理中…」，opacity `0.72`，防重复点击 |
| 二次确认 | 点击采纳前弹出确认：「采纳后将突出展示该回答」；若支持撤销，再单独确认撤销文案 |
| 无权限 | 非问题作者不显示采纳按钮，只展示已有采纳状态 |
| 失败 | toast「操作失败，请重试」；回答卡保持原状态 |

**实现边界：** 本轮纳入采纳/最佳回答流程和已确认的评论楼中楼；不新增回答排序策略配置、不新增奖励积分。

### 3.6 发布回答 FAB

| 属性 | 规格 |
|------|------|
| 形状 | `60rpx × 60rpx`；`border-radius: 50%` |
| 背景 | `var(--color-cta)` `#1E5A4C` |
| 文字「答」 | `font-size: 26rpx`；`font-weight: 600`；`#FFFFFF` |
| 阴影 | `var(--shadow-fab)` `0 10rpx 24rpx rgba(30,90,76,0.22)` |
| 定位 | `position: fixed; right: 32rpx; bottom: calc(120rpx + env(safe-area-inset-bottom))` |
| 触摸 | `hover-class` `opacity: 0.85`，禁止 `scale` |

### 3.7 回答相关状态覆盖

| 状态 | 处理 |
|------|------|
| 回答加载中（首次） | 「加载回答中…」文案改为 `<feed-skeleton>` 或 loading spinner |
| 回答加载失败 | `<state-block type="network">` + 重试 |
| 无回答 | `<state-block type="empty" title="还没有回答" description="来写第一个回答">`；无 actionText（靠 FAB 引导） |
| 加载更多（如有分页） | `<page-list-footer>` |
| 评论楼中楼 | 专业详情若包含评论区，同生活详情规则：展开时前端调用回复列表并挂载到一级评论 |
| 采纳中 | 当前回答卡的采纳按钮进入 disabled/loading 文案，其他回答按钮禁用 |
| 采纳成功 | 目标回答显示「最佳回答/已采纳」；列表状态与问题详情同步刷新 |
| 采纳失败 | toast 弱提示，保留原列表状态 |

> 当前实现「加载回答中…」为纯文案，建议升级为 skeleton 或小 loading 以保持一致性。

### 3.8 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 回答卡加 border | `pages/detail-pro/index.wxss` `.detail-page__ans` | 加 `border: 1rpx solid var(--color-border)` |
| 已采纳/最佳回答样式 | `.detail-page__ans-badge` | 改为 success 绿底白字圆角，文案可用「最佳回答」 |
| 采纳按钮与逻辑 | `pages/detail-pro/index.wxml|ts` | 问题作者显示采纳按钮；点击二次确认；调用采纳接口；成功后刷新回答列表 |
| 标签胶囊化 | `.detail-page__tag` | 圆角 `999rpx`，专业底色 `#E8F2EF` |
| FAB 阴影与颜色 | `.detail-page__fab` | 确认 `var(--color-cta)` + `var(--shadow-fab)` |
| 回答加载占位 | `detail-page__hint` | 替换为 `<feed-skeleton>` P2 |

---

## 四、回答详情页（`pages/answer-detail/index`）

### 4.1 页面目标

聚焦展示某一具体回答（含所属问题概要）及该回答的评论列表；支持发布回答（FAB），承接专业详情的最佳回答/采纳状态，并按已确认方案支持评论楼中楼展开。

### 4.2 信息区块

```
┌────────────────────────────────────┐
│  问题概要卡                        │
│    问题标题                        │
│    <author-row>                    │
│    院系/届标签                     │
│    问题正文（可选）                 │
├────────────────────────────────────┤
│  "回答" 区块标题                   │
│  当前回答卡（pinned）               │
│    "当前回答" 徽章                  │
│    <author-row>                    │
│    「已采纳」标记（可选）           │
│    问题作者采纳操作（可选）          │
│    回答正文                        │
├────────────────────────────────────┤
│  "其他回答 · N" 标题（有时）        │
│  其他回答卡列表                    │
├────────────────────────────────────┤
│  评论区卡片                        │
│    "评论" 小标题                   │
│    <comment-list>                  │
│    <comment-composer>              │
├────────────────────────────────────┤
│  fab-spacer                        │
│  发布回答 FAB                      │
└────────────────────────────────────┘
```

### 4.3 问题概要卡

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-brand-light)` `#E8F2EF`（区分「问题」与「回答」） |
| border | `1rpx solid rgba(30,90,76,0.15)` |
| 圆角 | `16rpx` |
| padding | `20rpx 24rpx` |
| margin | `12rpx 24rpx 0` |
| 标题 | `var(--font-title)` `34rpx / 600`；`var(--color-text-primary)` |
| 标签 chips | 专业底色规范，同 §3.3 |

### 4.4 「当前回答」徽章 `.answer-page__badge`

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-brand-muted)` `rgba(30,90,76,0.12)` |
| 字色 | `var(--color-brand)` |
| 字号 | `22rpx`；`font-weight: 500` |
| 圆角 | `999rpx`；`padding: 4rpx 14rpx` |
| 当前实现问题 | 若为纯文字无样式，需补胶囊 |

### 4.5 「已采纳」标记 `.answer-page__accepted`

| 属性 | 规格 |
|------|------|
| 与专业详情页一致 | 背景 `var(--color-success)`；字 `#FFFFFF`；圆角 `999rpx`；文案可为「最佳回答」 |

### 4.6 采纳操作

| 状态 | 处理 |
|------|------|
| 可采纳 | 当前用户是问题作者且当前回答未被采纳时，在回答卡底部显示「采纳」按钮 |
| 已采纳 | 显示 success 胶囊，隐藏采纳按钮 |
| 采纳中 | 按钮 disabled，文案「处理中…」 |
| 成功 | 当前回答切换为最佳回答态；返回专业详情后状态保持一致 |
| 失败 | toast「操作失败，请重试」；不改变当前回答状态 |

### 4.7 其他回答卡

- 布局同专业详情页回答卡，但无「已采纳」逻辑（当前回答已单独突出）
- 间距 `gap: 16rpx`

### 4.8 评论区卡片

| 属性 | 规格 |
|------|------|
| 外卡 `.answer-page__discuss` | 背景 `var(--color-card-bg)`；`border: 1rpx solid var(--color-border)`；圆角 `16rpx` |
| 评论小标题 | `font-size: 26rpx`；`font-weight: 600`；`var(--color-text-primary)` |
| `<comment-composer>` | 输入区占位色 `var(--color-text-tertiary)`；`padding-bottom: env(safe-area-inset-bottom)` |

### 4.9 评论楼中楼

| 状态 | 处理 |
|------|------|
| 默认 | 评论列表先展示一级评论；每条评论按回复数量显示「展开 N 条回复」 |
| 展开 | 触发 `loadreplies`，前端调用 `getReplyList(parentCommentId, contentId, answerId)`；loading 只出现在当前评论下 |
| 成功 | 回复缩进展示，二级回复显示被回复人昵称、时间、点赞/更多操作 |
| 失败 | 当前评论下显示重试，不影响回答正文和其他评论 |
| 收起 | 折叠展示但保留已加载数据，二次展开不重复请求，除非用户手动重试 |

**后端边界：** 不新增接口；使用现有一级评论列表和回复列表接口，前端负责拼装楼中楼结构。

### 4.10 状态覆盖

| 状态 | 处理 |
|------|------|
| 加载中（无数据） | 全屏 `<feed-skeleton>` |
| 加载失败 | `<state-block>` + 重试 |
| 参数无效 | `<state-block type="invalidData">` + 返回 |
| 评论加载中 | `<comment-list>` 内骨架 |
| 评论为空 | `empty-title="暂无评论" empty-desc="在回答下参与讨论"` |
| 回复展开中 | 当前评论内行内 loading |
| 回复加载失败 | 当前评论内重试 |
| 采纳中 | 当前回答采纳按钮禁用，保留页面可阅读 |

### 4.11 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 问题概要卡背景 | `pages/answer-detail/index.wxss` `.answer-page__question` | 改为 `var(--color-brand-light)` |
| 「当前回答」徽章胶囊 | `.answer-page__badge` | 添加胶囊样式，`var(--color-brand-muted)` 底 |
| 「已采纳」样式 | `.answer-page__accepted` | 与 `detail-pro` 保持一致 |
| 采纳按钮与逻辑 | `pages/answer-detail/index.wxml|ts` | 问题作者可在回答详情采纳当前回答；成功后更新当前回答状态 |
| 评论区卡 border | `.answer-page__discuss` | 加 `border: 1rpx solid var(--color-border)` |
| 楼中楼展开 | `components/comment-list/index.*`、`pages/answer-detail/index.ts` | 使用现有 `loadreplies` 事件和 `getReplyList`，前端拼装回复树 |

---

## 五、全组改造收益摘要

| 改动 | 优先级 | 收益 |
|------|--------|------|
| 内容卡统一加 border | P0 | 层级感清晰，告别无边界堆叠 |
| 已采纳/当前回答徽章语义化 | P0 | success 绿胶囊，即读即知 |
| 评论楼中楼展开 | P1 | 后端不改，前端按需加载回复，讨论关系更清晰 |
| 最佳回答采纳流程 | P1 | 专业问答闭环，承接已确认 3.3 |
| 生活/专业标签胶囊化 | P1 | 标签系统统一，区分业务语义 |
| 正文字号阶梯 | P1 | 阅读体验；来自补充检索 |
| 问题概要卡绿底区分 | P1 | 问题/回答层级一目了然 |
| 图片占位暖色 | P1 | 去除蓝灰模板感 |
| 回答加载骨架化 | P2 | 与加载体验统一 |
