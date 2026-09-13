# 个人内容组设计稿

> **分组：我的发布 · 我的点赞 · 我的收藏 · 浏览历史**  
> 关联：`MASTER.md`、`phase2-overview.md §3.1 个人内容组补充检索`。  
> 技术栈：微信小程序，样式 `rpx` + CSS 变量，与 `app.wxss` 对齐。

---

## 一、分组共享规则

| 规则 | 说明 |
|------|------|
| 页面底色 | `var(--color-bg)` `#F7F5F0` |
| 页面边距 | `padding: 24rpx 24rpx 48rpx`，与当前四页一致 |
| 列表卡片 | 统一复用 `<content-card>`；`showAuthor="{{true}}"`；卡片间距 `20rpx` |
| 卡片边界 | 由 `content-card` 统一加 `1rpx solid var(--color-border)`，页面不重复写阴影 |
| 列表页脚 | 统一复用 `<page-list-footer>`；加载、失败、到底文案 token 化 |
| 首次加载 | `<feed-skeleton variant="home">`；P2 可拆 `assets` variant |
| 错误状态 | `<state-block>` + 单一重试 CTA |
| 空状态 | `<state-block type="empty">`，每页文案区分动机；有明确可行动页面才给 CTA |
| 底部安全区 | `height: calc(24rpx + env(safe-area-inset-bottom))` 或当前 `min-height: 24rpx` |
| 触摸反馈 | 卡片和操作按钮使用 `hover-class` 透明度反馈，禁止 `scale` |
| 下拉刷新 | 若页面已启用则保留；不额外给详情/表单页引入下拉刷新 |

---

## 二、共享列表布局

```
┌────────────────────────────────────┐
│  可选顶部工具区 / 筛选区            │
├────────────────────────────────────┤
│  首次加载：feed-skeleton            │
│  错误：state-block + 重试            │
│  空：state-block                    │
│  有数据：content-card 列表           │
│          page-list-footer           │
│          safe-bottom                │
└────────────────────────────────────┘
```

### 2.1 列表容器

| 属性 | 规格 |
|------|------|
| `.mc__cards` / `.ml__cards` / `.bh__cards` | `display: flex; flex-direction: column; gap: 20rpx` |
| state 容器 | `padding: 48rpx 0`；内层由 `state-block` 控制 |
| safe-bottom | `height: env(safe-area-inset-bottom); min-height: 24rpx`；P1 统一为 `calc(24rpx + env(...))` |

### 2.2 内容卡复用

- 卡片标题、摘要、图片、作者、统计行均由 `<content-card>` 统一。
- 四页只传入列表数据和点击事件，不在页面层新增卡片样式。
- 图片占位底色按 `content-card` P1：`#F0EDE7`。
- 生活/专业标签按 MASTER：生活 `#F0EBE3 / #6B5B4F`，专业 `#E8F2EF / #1E5A4C`。

---

## 三、我的发布页（`pages/my-content/index`）

### 3.1 页面目标

展示当前用户发布过的内容，并支持按审核状态筛选。

### 3.2 信息区块

```
┌────────────────────────────────────┐
│  审核状态 tabs 卡片                 │
│  全部 / 审核中 / 已通过 / 已驳回     │
├────────────────────────────────────┤
│  内容列表 / 空态 / 错误 / 骨架       │
└────────────────────────────────────┘
```

### 3.3 审核状态 Tabs

| 状态 | 规格 |
|------|------|
| 外卡 `.mc__tabs` | `background: var(--color-card-bg)`；`border: 1rpx solid var(--color-border)`；`border-radius: 16rpx`；`padding: 20rpx 24rpx`；`gap: 12rpx` |
| 默认 chip `.mc__chip` | `padding: 10rpx 20rpx`；`border-radius: 999rpx`；`font-size: 24rpx`；字 `var(--color-text-secondary)`；背景 `var(--color-bg)`；边框 `1rpx solid var(--color-border)` |
| 激活 `.mc__chip--on` | 字 `var(--color-brand)`；背景 `var(--color-brand-muted)`；边框 `1rpx solid rgba(30,90,76,0.24)` |
| 触摸态 | `opacity: 0.85` |

### 3.4 审核语义

| 状态 | 建议样式 |
|------|----------|
| 审核中 | `var(--color-warning)` 文字或浅底胶囊 |
| 已通过 | `var(--color-success)` 文字或浅底胶囊 |
| 已驳回 | `var(--color-danger)` 文字或浅底胶囊 |
| 全部 | 默认品牌激活态 |

> 页面层不新增审核徽章字段；若 `content-card` 已展示审核状态，则只 token 化颜色。

### 3.5 状态覆盖

| 状态 | 处理 |
|------|------|
| 默认 | 显示 tabs + 列表 |
| 筛选中 | chip 切换后列表 skeleton 或保留旧数据加 footer loading |
| 空发布 | `state-block`：标题「还没有发布内容」；说明「发布一条内容，与大家一起交流」；CTA「去发布」 |
| 加载失败 | `state-block` + 重试 |
| 加载更多失败 | `page-list-footer` + 重试 |

### 3.6 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| tabs 卡片 border | `pages/my-content/index.wxss` `.mc__tabs` | 加 `border: 1rpx solid var(--color-border)` |
| chip 默认底色 | `.mc__chip` | `#f0f2f5` 改 `var(--color-bg)` 并加 border |
| chip 激活态 | `.mc__chip--on` | 保持 `var(--color-brand-muted)`，补 border |

---

## 四、我的点赞页（`pages/my-liked/index`）

### 4.1 页面目标

展示用户点过赞的内容，强化「回看感兴趣内容」的资产感。

### 4.2 信息区块

```
┌────────────────────────────────────┐
│  content-card 列表                  │
│  page-list-footer                   │
└────────────────────────────────────┘
```

### 4.3 页面差异

- 不需要顶部工具区，首屏直接进入列表。
- 空态不提供 CTA，避免新增搜索/推荐流程。
- 空态说明保持当前方向：「在首页或搜索里给喜欢的帖子点个赞吧」。

### 4.4 状态覆盖

| 状态 | 处理 |
|------|------|
| 首次加载 | `<feed-skeleton variant="home">` |
| 空 | `<state-block type="empty" title="还没有点赞内容" description="在首页或搜索里给喜欢的帖子点个赞吧">` |
| 错误 | `<state-block>` + 重试 |
| 有数据 | `content-card` 列表 + footer |

### 4.5 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 页面背景 | `pages/my-liked/index.wxss` `.ml` | 确认使用 `var(--color-bg)` |
| 卡片样式 | `components/content-card/index.wxss` | 由共享组件统一 |
| safe-bottom | `.ml__safe-bottom` | P1 统一为 `calc(24rpx + env(...))` |

---

## 五、我的收藏页（`pages/my-collect/index`）

### 5.1 页面目标

展示用户收藏的内容，定位为「干货/长期回看」列表。

### 5.2 页面差异

- 结构与点赞页一致。
- 空态文案偏「可回看」：标题「还没有收藏内容」；说明「遇到干货记得点收藏，方便以后回看」。
- 不新增分类、批量管理、文件夹等能力；这些属于大改建议。

### 5.3 状态覆盖

| 状态 | 处理 |
|------|------|
| 首次加载 | `<feed-skeleton variant="home">` |
| 空 | `<state-block type="empty" title="还没有收藏内容" description="遇到干货记得点收藏，方便以后回看">` |
| 错误 | `<state-block>` + 重试 |
| 有数据 | `content-card` 列表 + footer |

### 5.4 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 页面背景 | `pages/my-collect/index.wxss` `.mc` | 确认使用 `var(--color-bg)` |
| 列表间距 | `.mc__cards` | 保持 `gap: 20rpx` |
| safe-bottom | `.mc__safe-bottom` | P1 统一安全区写法 |

---

## 六、浏览历史页（`pages/browse-history/index`）

### 6.1 页面目标

展示最近浏览过的内容，并提供清空全部操作。

### 6.2 信息区块

```
┌────────────────────────────────────┐
│  顶部工具条卡片                     │
│    浏览记录                         │
│    清空全部                         │
├────────────────────────────────────┤
│  内容列表 / 空态 / 错误 / 骨架       │
└────────────────────────────────────┘
```

### 6.3 顶部工具条 `.bh__bar`

| 属性 | 规格 |
|------|------|
| 外卡 | `background: var(--color-card-bg)`；`border: 1rpx solid var(--color-border)`；`border-radius: 16rpx`；`padding: 20rpx 24rpx` |
| 标题 `.bh__bar-text` | `font-size: 26rpx`；`font-weight: 600`；`var(--color-text-primary)` |
| 清空 `.bh__clear` | `font-size: 28rpx`；`var(--color-brand)`；触摸 `opacity: 0.85` |
| 禁用 `.bh__clear--disabled` | `opacity: 0.45`；不可重复触发 |

### 6.4 清空交互

| 状态 | 处理 |
|------|------|
| 默认 | 有列表时显示工具条 |
| 点击清空 | 需二次确认弹窗（当前若已有逻辑则保持）；按钮进入 disabled |
| 清空中 | `.bh__clear--disabled`；文案可改「清空中」 |
| 清空成功 | 切换空态「暂无浏览历史」 |
| 清空失败 | toast + 按钮恢复 |

### 6.5 状态覆盖

| 状态 | 处理 |
|------|------|
| 首次加载 | `<feed-skeleton variant="home">` |
| 空 | `<state-block type="empty" title="暂无浏览历史" description="浏览帖子后会自动记录在这里">` |
| 错误 | `<state-block>` + 重试 |
| 有数据 | 工具条 + `content-card` 列表 + footer |

### 6.6 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 工具条卡片 border | `pages/browse-history/index.wxss` `.bh__bar` | 加 `border: 1rpx solid var(--color-border)` |
| 工具条标题颜色 | `.bh__bar-text` | 改 `var(--color-text-primary)`，加 `font-weight: 600` |
| 清空 disabled | `.bh__clear--disabled` | 保持 `opacity: 0.45`，逻辑层防重复点击 |

---

## 七、全组改造收益摘要

| 改动 | 优先级 | 收益 |
|------|--------|------|
| 四页统一 content-card + footer | P0 | 列表体验一致，减少重复样式 |
| `my-content` tabs 卡片加 border | P0 | 筛选区更清晰 |
| chips 默认底色暖化 | P0 | 去蓝灰模板感 |
| 浏览历史工具条 token 化 | P1 | 操作区层级清晰 |
| safe-bottom 写法统一 | P1 | 避免底部遮挡 |
| 单独拆 `assets` 骨架 variant | P2 | 加载态更贴近真实列表 |

