# 用户主页设计稿

> **分组：用户身份组 · 用户主页**  
> 页面：`pages/user-profile/index`  
> 关联：`MASTER.md`、`phase2-overview.md §3.1 用户身份组补充检索`。  
> 技术栈：微信小程序，样式 `rpx` + CSS 变量，与 `app.wxss` 对齐。

---

## 一、页面目标

用户主页用于展示一个校友/成员的身份信息、关系状态和公开发布内容。设计目标是「可信、有温度、低噪音」：突出头像、认证、院系/届信息和发布内容，不新增私信、复杂动态分区等未实现能力。

---

## 二、信息区块

```
┌────────────────────────────────────┐
│  加载 / 错误 / 参数无效状态          │
├────────────────────────────────────┤
│  用户头部 hero 卡片                 │
│    头像                            │
│    昵称 + 认证徽章                  │
│    院系 / 届                       │
│    统计：关注 / 粉丝 / 发布          │
│    操作按钮：编辑资料 / 关注 / 已关注 │
├────────────────────────────────────┤
│  TA 的发布                          │
│    错误 / 空态 / content-card 列表   │
│    page-list-footer                 │
└────────────────────────────────────┘
```

---

## 三、全局布局

| 区域 | 规格 |
|------|------|
| 页面 `.up` | `min-height: 100vh`；`padding: 24rpx 24rpx 48rpx`；背景 `var(--color-bg)` |
| 状态区 `.up__state` | `padding: 48rpx 0` |
| 卡片间距 | 头部卡与列表区 `20rpx` |
| safe-bottom | `height: calc(24rpx + env(safe-area-inset-bottom))` |

---

## 四、用户头部 Hero 卡片

### 4.1 Hero 外卡 `.up__hero`

| 属性 | 规格 |
|------|------|
| 背景 | `linear-gradient(180deg, var(--color-accent-warm-light) 0%, var(--color-card-bg) 72%)` |
| border | `1rpx solid var(--color-border)` |
| 圆角 | `var(--radius-card)` `16rpx` |
| padding | `28rpx 24rpx 24rpx` |
| 阴影 | 默认不用阴影；若需层级可用 `var(--shadow-card)`，不叠加光晕 |

> 来源：MASTER 品牌方向「校园记忆（暖纸色、砖赭点缀）+ 社群可信」。

### 4.2 顶部布局 `.up__hero-top`

| 元素 | 规格 |
|------|------|
| 布局 | `display: flex; gap: 20rpx; align-items: flex-start` |
| 头像 `.up__avatar` | `120rpx × 120rpx`；`border-radius: 50%`；`border: 4rpx solid rgba(30,90,76,0.14)`；占位底 `#F0EDE7` |
| 主信息 `.up__hero-main` | `flex: 1; min-width: 0` |
| 昵称 `.up__nickname` | `font-size: var(--font-title)` `34rpx`；`font-weight: 600`；颜色 `var(--color-text-primary)`；长昵称省略 |
| 院系/届 `.up__sub` | `font-size: var(--font-caption)` `24rpx`；颜色 `var(--color-text-secondary)`；`line-height: 1.5` |

### 4.3 认证徽章 `.up__auth`

| 状态 | 样式 |
|------|------|
| 已认证 | 字 `var(--color-success)`；底 `rgba(45,122,79,0.10)`；边框 `1rpx solid rgba(45,122,79,0.18)` |
| 未认证/未知 | 字 `var(--color-text-secondary)`；底 `var(--color-bg)`；边框 `1rpx solid var(--color-border)` |
| 尺寸 | `font-size: 22rpx`；`padding: 4rpx 12rpx`；`border-radius: 999rpx` |
| 当前实现问题 | 圆角为 `8rpx`，建议改为胶囊，与其它状态标签一致 |

### 4.4 统计区 `.up__stats`

| 属性 | 规格 |
|------|------|
| 布局 | `display: flex; flex-wrap: wrap; gap: 20rpx 28rpx` |
| 上分割线 | `border-top: 1rpx solid var(--color-divider)` |
| 数字 `.up__stat-num` | `font-size: 30rpx`；`font-weight: 600`；`var(--color-text-primary)` |
| 标签 `.up__stat-label` | `font-size: 22rpx`；`var(--color-text-tertiary)` |
| 语义 | 不使用蓝色块面；统计只作信息，不设计成强入口 |

---

## 五、操作按钮

### 5.1 按钮通用 `.up__btn`

| 属性 | 规格 |
|------|------|
| 高度 | `72rpx` |
| 圆角 | `var(--radius-button)` `999rpx`，替代当前 `12rpx` |
| 字号 | `28rpx`；`font-weight: 500` |
| 宽度 | `100%` |
| 触摸态 | `opacity: 0.85` |
| busy 态 | `opacity: 0.72`；禁用重复点击 |

### 5.2 按钮状态

| 状态 | 文案 | 样式 |
|------|------|------|
| 自己主页 | 编辑资料 | Ghost：白底，字 `var(--color-brand)`，边框 `rgba(30,90,76,0.35)` |
| 未关注 | 关注 | Primary：底 `var(--color-cta)`，字 `#FFFFFF` |
| 已关注 | 已关注 | Secondary：底 `var(--color-brand-light)`，字 `var(--color-brand)`，边框 `1rpx solid rgba(30,90,76,0.18)` |
| 请求中 | 保持原文案 | `disabled` + `.up__btn--busy` |

> 用户身份组补充检索强调关注态要有文案 + 颜色双表达，因此「已关注」不能只靠颜色变化。

---

## 六、TA 的发布列表

### 6.1 区块标题 `.up__section-title`

| 属性 | 规格 |
|------|------|
| 文案 | 「TA 的发布」；若是自己主页也保持当前文案，不新增分支 |
| 字号 | `28rpx`；`font-weight: 600` |
| 颜色 | `var(--color-text-primary)` |
| margin | `0 0 16rpx 4rpx` |

### 6.2 列表

| 属性 | 规格 |
|------|------|
| 卡片 | `<content-card showAuthor="{{false}}">`，避免在用户主页重复作者信息 |
| 间距 | `.up__cards { gap: 20rpx }` |
| 页脚 | `<page-list-footer>`，加载/失败/到底状态统一 |
| 卡片边界 | 由 `content-card` 统一加 `1rpx solid var(--color-border)` |

### 6.3 列表状态

| 状态 | 处理 |
|------|------|
| 列表加载失败 | `<state-block type="server">`；若需认证则 CTA「去认证」，否则「重试」 |
| 列表为空 | `<state-block type="empty" title="暂无发布" description="TA 暂时还没有发布内容">` |
| 首次加载 | 页面级 `<feed-skeleton variant="home">` |
| 加载更多 | `<page-list-footer loading-more>` |
| 加载更多失败 | `<page-list-footer load-error>` + retry |

---

## 七、页面状态覆盖

| 状态 | 处理 |
|------|------|
| 参数无效 | `<state-block type="invalidData" title="参数无效" description="请从有效入口进入用户主页">`；无 CTA |
| 主页加载中 | `<feed-skeleton variant="home">` |
| 主页加载失败 | `<state-block>` + 重试 |
| 有 profile | hero + 发布列表 |
| 关注请求中 | 按钮 disabled，`opacity: 0.72`，防重复触发 |
| 关注成功 | 按钮文案切换为「已关注」，样式切 secondary |
| 取消关注成功 | 按钮文案切换为「关注」，样式切 primary |

---

## 八、前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| hero 背景 | `pages/user-profile/index.wxss` `.up__hero` | 加暖色渐变 `--color-accent-warm-light -> #FFF` |
| hero border | `.up__hero` | 加 `border: 1rpx solid var(--color-border)` |
| 头像占位 | `.up__avatar` | `#e8eaef` 改 `#F0EDE7`，加墨绿浅边框 |
| 文字 token | `.up__nickname`, `.up__stat-num`, `.up__section-title` | `var(--color-text)` 改 `var(--color-text-primary)` |
| 认证徽章 | `.up__auth`, `.up__auth--muted` | 改胶囊圆角，已认证用 success 语义 |
| 统计分割线 | `.up__stats` | `rgba(0,0,0,0.06)` 改 `var(--color-divider)` |
| 按钮圆角 | `.up__btn` | `12rpx` 改 `999rpx` |
| 已关注按钮 | `.up__btn--secondary` | 改 `var(--color-brand-light)` 背景 + `var(--color-brand)` 字 |
| safe-bottom | `.up__safe-bottom` | 统一 `calc(24rpx + env(safe-area-inset-bottom))` |

---

## 九、需确认但不纳入默认稿

| 建议 | 原因 |
|------|------|
| 私信入口 | 当前无路由/接口，不默认画入 hero 操作区 |
| 内容分区 tabs（发布/回答/收藏） | 会改变用户主页信息架构，需要接口支持 |
| 关注者列表入口 | 当前统计仅作信息展示；若可点击需新增页面或路由 |
| 个人动态时间线 | 与「TA 的发布」重复，且会扩大页面复杂度 |

---

## 十、改造收益摘要

| 改动 | 优先级 | 收益 |
|------|--------|------|
| hero 暖色渐变 + border | P0 | 身份页更有校园温度和层级 |
| 头像占位暖化 | P0 | 去蓝灰模板感 |
| 认证徽章 success 语义 | P0 | 可信感增强 |
| 关注按钮状态统一 | P0 | 状态清晰，符合补充检索 |
| 统计区 token 化 | P1 | 视觉更稳 |
| 按钮圆角与全局按钮一致 | P1 | 操作区统一 |
| 私信/复杂分区剥离到需确认 | P1 | 不改现有主流程 |

