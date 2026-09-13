# 搜索组设计稿

> **分组：搜索页 · 搜索结果页 · AI 搜索详情页**  
> 关联：`MASTER.md`、`phase2-overview.md §3.1 搜索组补充检索`。  
> 技术栈：微信小程序，样式 `rpx` + CSS 变量，与 `app.wxss` 对齐。

---

## 一、分组共享规则

| 规则 | 说明 |
|------|------|
| 顶部搜索框组件 | 全组统一使用 `<search-input-bar>`，自带返回/清除/确认逻辑，禁止在结果页或 AI 详情页另起一套输入框 |
| 背景色 | `var(--color-bg)` `#F7F5F0`，卡片/区块 `var(--color-card-bg)` `#FFFFFF` |
| 结果列表卡片 | 复用 `<content-card>`，显示 `showAuthor="true"`；卡片纵向间距 `20rpx` |
| 空状态结构 | `<state-block>` 三件套（标题 + 说明 + 单一 CTA）；无结果必须提示换词/换分区，**不得只输出"0 条结果"** |
| 错误状态 | 复用 `<state-block type="network|server">`，提供重试 CTA |
| 加载状态 | `<feed-skeleton variant="home" count="3">`；等待首次数据时全屏骨架；已有数据时底部 `<page-list-footer loading-more>` |
| AI 区域强调色 | 用 `var(--color-brand-light)` `#E8F2EF` 作底区分；**禁止**电蓝/紫渐变 |
| 触摸反馈 | `hover-class` 降透明度 `0.85`，**禁止** `scale` |
| 分类切换 | `<content-type-tabs>` 下划线 `var(--color-brand)`；激活文字 `var(--color-brand)` |
| 推荐模块 | 搜索页已确认新增轻量推荐，优先热门关键词 + 热门问题；热门校友仅在有数据时显示 |

---

## 二、搜索页（`pages/search/index`）

### 2.1 页面目标

用户进入搜索场景的起始页：展示历史关键词，并用轻量热门推荐缓解空历史和无输入冷启动。

### 2.2 信息区块

```
┌────────────────────────────────────┐
│  <search-input-bar>  ← 自动聚焦     │
├────────────────────────────────────┤
│  <content-type-tabs>（分区筛选）    │
├────────────────────────────────────┤
│  搜索历史卡片                       │
│    标题行：时钟图标 + "搜索历史" + 清空 │
│    历史 chip 列表 / 空文案 / 骨架屏  │
├────────────────────────────────────┤
│  热门推荐卡片（已确认新增）           │
│    热门关键词 chips                 │
│    热门问题列表                     │
│    热门校友横滑（有数据时）           │
└────────────────────────────────────┘
```

### 2.3 布局与尺寸

| 区域 | 规格 |
|------|------|
| 页面背景 | `var(--color-bg)` |
| 分区 tabs 包裹 | `padding: 4rpx 24rpx 0` |
| body 区 | `padding: 16rpx 24rpx 32rpx` |
| 历史卡片 | `padding: 20rpx 22rpx 22rpx`；圆角 `var(--radius-card)` `16rpx`；无阴影，加 `1rpx solid var(--color-border)` |
| 热门推荐卡片 | `margin-top: 20rpx`；`padding: 20rpx 22rpx 22rpx`；白底 + `1rpx solid var(--color-border)`；圆角 `16rpx` |

### 2.4 时钟图标（CSS 几何）

```css
/* 现有实现已正确，以下为规范说明 */
/* 圆圈：28rpx × 28rpx，border 3rpx，颜色 var(--color-text-tertiary) */
/* 分针：after 伪元素，8rpx × 2rpx，opacity 0.72 */
```

> 来源：MASTER.md §5「CSS 几何自绘 Tab 图标，线宽 3rpx」。

### 2.5 历史 Chip

| 状态 | 样式 |
|------|------|
| 默认 | `background: var(--color-bg)` `#F7F5F0`；`border: 1rpx solid var(--color-border)`；圆角 `12rpx`；`padding: 14rpx 22rpx`；字 `26rpx` `var(--color-text-primary)` |
| 触摸 | `background: var(--color-brand-muted)` `rgba(30,90,76,0.12)`；`border-color: rgba(30,90,76,0.2)` |
| 当前实现问题 | `background-color: #f5f7fa` 需替换为 `var(--color-bg)` |

### 2.6 清空按钮

- `font-size: 26rpx`；颜色 `var(--color-text-tertiary)`
- 触摸 `opacity: 0.82`
- 触摸区 `padding: 8rpx 4rpx`（保证 ≥44rpx 触摸高度）

### 2.7 历史标题行

- `border-bottom: 1rpx solid var(--color-divider)` `rgba(26,29,33,0.06)`
- 标题 `font-size: 30rpx`；`font-weight: 600`；颜色 `var(--color-text-primary)`

### 2.8 状态覆盖

| 状态 | 处理 |
|------|------|
| 默认（有历史） | 历史 chip 列表 + 热门推荐卡片，`flex-wrap: wrap`，间距 `16rpx` |
| 空历史 | 历史区文案「还没有搜索记录，试试输入关键词」；下方仍展示热门推荐，避免页面过空 |
| 加载中 | `<feed-skeleton variant="home" count="3">` 填充历史卡内容区 |
| 加载错误 | `<state-block>` 带重试 |
| 清空触发 | 清空按钮点击→历史清空→切换到空历史态，**无 toast**（操作即时可见） |

### 2.9 热门推荐模块（已确认新增）

| 区块 | 规格 |
|------|------|
| 标题行 | 左侧标题「大家在搜」或「热门推荐」；右侧可选「换一批」，颜色 `var(--color-brand)`，触摸 `opacity: 0.85` |
| 热门关键词 | 胶囊 chip；背景 `var(--color-brand-light)`；字 `var(--color-brand)`；点击后按当前分区发起搜索 |
| 热门问题 | 2-3 条轻列表；标题 `28rpx / 500`，摘要或热度 `24rpx / --color-text-tertiary`；点击进入对应详情或搜索结果 |
| 热门校友 | 仅有推荐用户数据时显示；横向头像 + 昵称 + 院系/届；点击进入 `pages/user-profile/index` |
| 空数据 | 整个推荐卡隐藏，不显示空壳；搜索页仍保留历史卡 |
| 错误 | 推荐加载失败不阻断搜索，静默隐藏或显示「推荐暂不可用」弱文案，不展示重错误态 |

**边界：** 不做输入联想、不做高级筛选、不把搜索页改成完整推荐流。推荐模块高度控制在首屏可见范围内，保证搜索输入仍是主任务。

### 2.10 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 历史 chip 背景色 | `pages/search/index.wxss` `.search-page__tag` | 将 `#f5f7fa` 改为 `var(--color-bg, #F7F5F0)` |
| 历史卡加 border | `.search-page__history-card` | 加 `border: 1rpx solid var(--color-border)` |
| 空历史文案颜色 | `.search-page__history-empty` | 确认使用 `var(--color-text-tertiary)` |
| 热门推荐数据 | `pages/search/index.ts` | 新增热门关键词/问题/校友加载与点击处理；无数据时隐藏模块 |
| 热门推荐样式 | `pages/search/index.wxml|wxss` | 新增 `.search-page__recommend-card`、`.search-page__hot-chip`、`.search-page__hot-question`、`.search-page__alumni-scroll` |

---

## 三、搜索结果页（`pages/search-result/index`）

### 3.1 页面目标

展示搜索关键词对应的内容列表，含 AI 摘要卡；支持分区筛选和加载更多。

### 3.2 信息区块

```
┌────────────────────────────────────┐
│  关键词 bar（可点击返回修改）        │
├────────────────────────────────────┤
│  <content-type-tabs>（分区）        │
├────────────────────────────────────┤
│  <ai-summary-card>（AI 摘要）       │
├────────────────────────────────────┤
│  "帖子" 小标题                      │
│  content-card 列表                  │
│  <page-list-footer>                 │
│  safe-bottom                        │
└────────────────────────────────────┘
```

### 3.3 关键词 Bar

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-card-bg)` `#FFFFFF`；圆角 `16rpx`；`margin: 8rpx 24rpx 0`；`padding: 18rpx 20rpx` |
| 左 label | `font-size: 24rpx`；`var(--color-text-tertiary)` |
| 关键词文本 | `flex: 1`；`font-size: 28rpx`；`var(--color-text-primary)`；溢出省略 |
| 修改文字 | `font-size: 24rpx`；`var(--color-brand)` `#1E5A4C` |
| 触摸态 | `opacity: 0.92`（已有，保持） |
| border | 加 `1rpx solid var(--color-border)` |

### 3.4 AI 摘要卡（`<ai-summary-card>`）

| 状态 | 处理 |
|------|------|
| 加载中 | 卡片内骨架：两行文字占位，颜色 `#E8F2EF` |
| 有摘要 | 卡片背景 `var(--color-brand-light)` `#E8F2EF`；`border: 1rpx solid rgba(30,90,76,0.15)`；圆角 `16rpx`；`padding: 20rpx 24rpx` |
| 无摘要/错误 | 卡片显示「AI 摘要暂不可用」，`var(--color-text-tertiary)` |
| 强调色 | 仅用 `--color-brand-light` 底；**禁止**电蓝/紫渐变背景 |
| 点击行为 | 跳转 `search-ai-detail` |

来源：`phase2-overview.md §2.2`「产品方禁止项：AI 页用 `--color-brand-light` 底区分即可」。

### 3.5 结果列表

| 区域 | 规格 |
|------|------|
| 小标题「帖子」 | `font-size: 28rpx`；`font-weight: 600`；`var(--color-text-primary)`；`padding: 8rpx 0 12rpx` |
| 卡片间距 | `gap: 20rpx` |
| 底部 footer | `<page-list-footer>` token 色统一（加载中颜色 `var(--color-brand)`；结束态 `var(--color-text-tertiary)`） |
| safe-bottom | `height: calc(24rpx + env(safe-area-inset-bottom))` |

### 3.6 状态覆盖

| 状态 | 视觉 |
|------|------|
| 首次加载（无数据） | 全屏 `<feed-skeleton variant="home">` |
| 加载错误（无数据） | `<state-block type="network|server">` + 重试 CTA |
| 无结果 | `<state-block type="empty" title="未找到相关帖子" description="换个关键词或切换分区试试">`；CTA 「返回搜索」；来源：补充检索「无结果需说明+动作」 |
| 关键词无效 | `<state-block type="invalidData">` + 返回 CTA（已有实现，颜色 token 化） |
| 加载更多中 | `<page-list-footer loading-more>` |
| 加载更多错误 | `<page-list-footer load-error>` + 重试 |
| 全部加载完 | `<page-list-footer finished>` 文案「已经到底了」 |

### 3.7 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 关键词 bar 加 border | `pages/search-result/index.wxss` `.sr__kw` | 加 `border: 1rpx solid var(--color-border)` |
| AI 卡片背景色 | `components/ai-summary-card` | 确认用 `--color-brand-light`，去除任何蓝色 |
| 空状态文案 | `pages/search-result/index.wxml` | `description` 已有「换个关键词或切换分区试试」，保持 |

---

## 四、AI 搜索详情页（`pages/search-ai-detail/index`）

### 4.1 页面目标

完整展示某关键词的 AI 摘要全文，附相关来源卡片。

### 4.2 信息区块

```
┌────────────────────────────────────┐
│  头部卡：关键词 + 分区标签          │
├────────────────────────────────────┤
│  正文卡：「完整总结」标题 + 总结文本 │
├────────────────────────────────────┤
│  相关来源标题                       │
│  content-card 列表                  │
│  safe-bottom                        │
└────────────────────────────────────┘
```

### 4.3 头部卡

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-brand-light)` `#E8F2EF`；`border: 1rpx solid rgba(30,90,76,0.15)`；圆角 `16rpx` |
| 关键词 | `font-size: 30rpx`；`font-weight: 600`；`var(--color-text-primary)` |
| 分区标签 `.sad__tag` | 胶囊形；背景 `var(--color-brand-muted)`；字色 `var(--color-brand)`；`font-size: 22rpx`；`padding: 4rpx 14rpx`；`border-radius: 999rpx` |
| margin | `margin: 16rpx 24rpx 0` |

### 4.4 正文卡

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-card-bg)` `#FFFFFF`；`border: 1rpx solid var(--color-border)`；圆角 `16rpx` |
| 标题「完整总结」 | `font-size: var(--font-title)` `34rpx`；`font-weight: 600`；`var(--color-text-primary)`；`margin-bottom: 16rpx` |
| 总结文本 | `font-size: var(--font-body)` `28rpx`；`line-height: 1.55`；`var(--color-text-primary)`；`user-select`（可复制） |
| 暂无总结态 | 「暂未生成总结」颜色 `var(--color-text-tertiary)`；副文 `var(--color-text-tertiary)` |
| margin | `margin: 16rpx 24rpx 0` |

> 来源：补充检索「详情正文 `--font-body` 行高 1.5–1.75」→ 取 `1.55`。

### 4.5 相关来源

| 属性 | 规格 |
|------|------|
| 区块标题 `.sad__sources-title` | `font-size: 26rpx`；`font-weight: 600`；`var(--color-text-primary)`；`margin: 20rpx 24rpx 8rpx` |
| 卡片列表 | `margin: 0 24rpx`；间距 `20rpx`；复用 `<content-card>` |

### 4.6 状态覆盖

| 状态 | 处理 |
|------|------|
| 加载中 | 全屏 `<feed-skeleton variant="home" count="3">` |
| 加载失败（无 summary） | `<state-block type="network|server">` + 重试 |
| 加载成功 | `<scroll-view scroll-y>`（已有实现，保持） |
| 总结为空 | 正文区显示「暂未生成总结」占位文案 |

### 4.7 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 头部卡背景 | `pages/search-ai-detail/index.wxss` `.sad__head` | 替换为 `background: var(--color-brand-light)` |
| 分区标签样式 | `.sad__tag` | 胶囊形，`var(--color-brand-muted)` 底 |
| 正文行高 | `.sad__content` | 确认 `line-height: 1.55` |
| 加 border | `.sad__head`, `.sad__body` | 加 `border: 1rpx solid var(--color-border)` |

---

## 五、全组改造收益摘要

| 改动 | 优先级 | 收益 |
|------|--------|------|
| 历史 chip 暖色底 + border | P0 | 统一品牌，告别灰白模板感 |
| AI 摘要卡绿底区分 | P0 | 去除蓝色，视觉一致 |
| 无结果文案引导 | P0 | 用户有恢复路径，不卡死胡同 |
| 热门推荐模块 | P1 | 缓解冷启动，承接已确认 2.1 |
| 关键词 bar 加 border | P1 | 层级清晰 |
| AI 详情头部绿底 | P1 | 视觉连贯 |
| 骨架 variant 统一 | P2 | 可后续拆 `search` variant |
