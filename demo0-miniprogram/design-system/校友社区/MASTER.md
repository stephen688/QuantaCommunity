# 校友社区 · 全局设计系统（MASTER）

> **层级规则：** 做具体页面时先查 `design-system/校友社区/pages/[page].md`；若存在则覆盖本文件对应条目，否则严格遵循下文。
>
> **技术栈：** 微信小程序（`demo0-miniprogram`），样式以 `page` 级 CSS 变量 + `rpx` 为主，与 `miniprogram/app.wxss` 对齐。

---

## 1. UI-UX-Pro-Max 检索摘要与取舍

### 1.1 已执行的检索

| 检索 | 命令/关键词 | 主要命中 |
|------|-------------|----------|
| 设计系统 | `alumni community education social warm trustworthy mobile app` | Community/Forum Landing；暖色欢迎；活动态绿色 |
| 补充 | `alumni university community warm professional trustworthy` | 学术气质字体方向（Garamond 系） |
| color | `education community social feed mobile` | 社区紫 / 社交玫红两套（均未直接采用） |
| typography | `professional warm readable mobile` | **Corporate Trust**（Lexend + Source Sans 3）；**Noto Sans SC**（中文） |
| ux | `loading pull refresh skeleton empty state` | 空状态需引导动作；导航需明确当前态；慎用无意义下拉刷新 |

### 1.2 采纳的原则

- **产品模式：** Community/Forum — 突出活跃成员、内容预览、低门槛参与（对应首页推荐流、关注流、发布 CTA）。
- **色彩策略：** 暖色背景 + 人像/头像增加「人味」；**活动/成功态用绿色**，不用装饰性光晕。
- **动效：** 状态切换 **150–300ms**；hover 用颜色/透明度，避免布局位移。
- **反模式（工具清单 + 产品约束）：** 不用 emoji 当图标；不忽略对比度；不 instant 切态；关注态可见。
- **额外禁止（产品方要求）：** 大面积电蓝渐变、玻璃拟态堆砌、无意义光晕、模板化圆角卡片墙。

### 1.3 明确不采纳的检索结果

| 检索推荐 | 不采纳原因 |
|----------|------------|
| Primary `#E11D48` + 玫粉背景 | 偏年轻娱乐社交，不像「校友可信」 |
| Baloo 2 / Comic Neue | 儿童教育气质，与「适度正式」冲突 |
| Vibrant & Block-based 高饱和块面 | 易落入泛社交 App 模板感 |
| `#7C3AED` 社区紫 + OLED Dark 为主 | 与当前浅色小程序、校园记忆意象不符 |
| 发布按钮蓝紫渐变（现状 `#2f6bff`） | 典型「AI/SaaS 电蓝」，需替换 |

### 1.4 项目语境下的品牌方向（定稿）

**意象：** 校园记忆（暖纸色、砖赭点缀）+ 社群可信（深墨绿主色，克制、非冷科技蓝）。

**一句话：** 像校友录与布告栏的结合体——温暖、可读、略正式，轻社交不喧闹。

---

## 2. Design Token 总览（落地用）

### 2.1 色板

| Token | Hex | 映射现有变量 | 语义 |
|-------|-----|--------------|------|
| `--color-brand` | `#1E5A4C` | 替换原 `#2f6bff` | 主品牌：深墨绿（连接、成长、可信） |
| `--color-brand-light` | `#E8F2EF` | 新增 | 主色浅底：选中 Tab、标签底 |
| `--color-brand-muted` | `rgba(30, 90, 76, 0.12)` | 新增 | Chip 激活、轻强调 |
| `--color-accent-warm` | `#B45309` | 新增 | 暖点缀：校友身份、重要标签（非主 CTA） |
| `--color-accent-warm-light` | `#FDF6EC` | 新增 | 我的页顶区渐变可用 |
| `--color-cta` | `#1E5A4C` | 与 brand 同系 | 主按钮、发布凸起钮（纯色，**禁止**蓝紫渐变） |
| `--color-bg` | `#F7F5F0` | 替换原 `#f5f6f8` | 页面暖灰底 |
| `--color-card-bg` | `#FFFFFF` | 保持 | 卡片/输入区 |
| `--color-surface-elevated` | `#FFFFFF` | 新增 | TabBar、吸顶栏 |
| `--color-text-primary` | `#1A1D21` | 微调原 `#1f2329` | 标题、正文主色 |
| `--color-text-secondary` | `#5C6370` | 微调原 `#646a73` | 摘要、副文案 |
| `--color-text-tertiary` | `#8C939E` | 微调原 `#8f959e` | 时间、计数、占位 |
| `--color-text-disabled` | `#B8BFC8` | 新增 | 禁用文案 |
| `--color-border` | `rgba(26, 29, 33, 0.08)` | 新增 | 卡片描边、分割线 |
| `--color-divider` | `rgba(26, 29, 33, 0.06)` | 新增 | 列表弱分割 |
| `--color-success` | `#2D7A4F` | 新增 | 认证通过、活动活跃（采纳 Pro-Max「activity green」） |
| `--color-warning` | `#B45309` | 新增 | 审核中 |
| `--color-danger` | `#C43D3D` | 新增 | 错误、审核不通过、角标可保留红 |
| `--color-unread` | `#C43D3D` | Tab 角标 | 未读（与 danger 同系，保持识别） |

**生活 / 专业分区（保留业务语义，弱化饱和）：**

| 类型 | 标签底 | 标签字 |
|------|--------|--------|
| 生活 | `#F0EBE3` | `#6B5B4F` |
| 专业 | `#E8F2EF` | `#1E5A4C` |

### 2.2 字体（小程序）

微信端**不引入 Google Fonts**，以系统字体栈实现 Pro-Max「可读、可信」方向：

```css
font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Helvetica Neue",
  "Noto Sans SC", "Microsoft YaHei", sans-serif;
```

| 阶梯 | rpx | px≈(375) | 字重 | 行高 | 用途 |
|------|-----|----------|------|------|------|
| `--font-display` | 40 | 20 | 600 | 1.25 | 关注页标题、大状态标题 |
| `--font-title` | 34 | 17 | 600 | 1.35 | 卡片标题、导航强调 |
| `--font-body` | 28 | 14 | 400 | 1.55 | 正文、列表摘要（= 现有 `page` 默认） |
| `--font-body-medium` | 28 | 14 | 500 | 1.55 | 分类 Tab 文案 |
| `--font-caption` | 24 | 12 | 400 | 1.45 | 时间、计数、脚标 |
| `--font-label` | 22 | 11 | 500 | 1.2 | TabBar 文案、角标旁辅助 |

### 2.3 圆角

| Token | 值 | 用途 |
|-------|-----|------|
| `--radius-card` | `16rpx` | 内容卡、通知卡（保持现状） |
| `--radius-chip` | `999rpx` | 类型切换、标签 |
| `--radius-input` | `12rpx` | 输入框内缘（发布页可略增） |
| `--radius-button` | `999rpx` | 主按钮、发布底栏大钮 |
| `--radius-avatar` | `50%` | 头像 |
| `--radius-publish-fab` | `56rpx` | 中间发布圆钮（112rpx 直径） |

### 2.4 阴影（克制，无玻璃）

| Token | 值 | 用途 |
|-------|-----|------|
| `--shadow-none` | none | 默认列表卡（描边为主） |
| `--shadow-card` | `0 2rpx 12rpx rgba(26, 29, 33, 0.06)` | 可选：我的页头部卡片 |
| `--shadow-fab` | `0 10rpx 24rpx rgba(30, 90, 76, 0.22)` | 发布 FAB（绿色系阴影，非蓝） |

**禁止：** `backdrop-filter` 毛玻璃、多层发光 `box-shadow` 装饰。

### 2.5 间距基线（与现有一致，补语义）

| Token | rpx | 用途 |
|-------|-----|------|
| `--space-xs` | 8 | 图标与文字、紧凑行内 |
| `--space-sm` | 16 | 卡片内小模块间距 |
| `--space-md` | 24 | 页面水平边距、卡片 padding（现状） |
| `--space-lg` | 32 | 区块之间 |
| `--space-xl` | 48 | 空状态上下留白 |
| `--space-tabbar-safe` | `120rpx + env(safe-area-inset-bottom)` | 列表底部（各页已用） |

**页面水平边距：** 统一 `24rpx`（首页/关注/消息/我的已对齐）。

**卡片列表纵向间距：** `20rpx`（`home__cards` / `follow__cards` / `nt__list`）。

### 2.6 动效

| Token | 值 | 用途 |
|-------|-----|------|
| `--duration-fast` | `150ms` | hover-class、按压反馈 |
| `--duration-normal` | `220ms` | Tab 切换、Chip 激活 |
| `--duration-slow` | `300ms` | 骨架屏淡出（若做） |
| `--ease-standard` | `cubic-bezier(0.4, 0, 0.2, 1)` | 通用 |

**按压：** 使用 `hover-class` 降透明度至 `0.85`，**禁止** `transform: scale` 导致布局跳动。

---

## 3. 组件状态规范（全局）

所有可交互组件需覆盖：

| 状态 | 视觉规则 |
|------|----------|
| 默认 | 上文 Token |
| 悬停/触摸 | `opacity: 0.85` 或背景加深 4–8%（`hover-class`） |
| 按下 | 同悬停或略深，无 scale |
| 禁用 | 文案 `--color-text-disabled`；按钮背景 `#E8EBEF`；不可点击 |
| 加载 | 主按钮 `loading`；列表用 `feed-skeleton` / 骨架 variant |
| 错误 | 文案 `--color-danger`；可重试链使用 `--color-brand` |
| 空 | `state-block`：标题 + 说明 + **单一主操作**（采纳 Pro-Max 空状态 UX） |

### 3.1 按钮

| 类型 | 背景 | 字色 | 高 | 圆角 |
|------|------|------|-----|------|
| Primary | `--color-cta` | `#FFF` | 84rpx | `--radius-button` |
| Secondary | transparent | `--color-brand` | 72rpx | 边框 `1rpx solid --color-brand` |
| Ghost | `--color-brand-light` | `--color-brand` | 64rpx | chip |

### 3.2 内容卡 `content-card`

- 背景 `#FFF`，圆角 `16rpx`，padding `24rpx`。
- 默认**无阴影**，可选 `1rpx` `--color-border`（美化阶段建议加上，增强层级）。
- 生活/专业角标用 2.1 分区色，不用高饱和玫红/电蓝。

### 3.3 TabBar（`custom-tab-bar`）

- 背景 `#FFF`，顶边 `--color-border`。
- 选中：图标/字 `--color-brand`；未选 `#8C939E`。
- 发布钮：**纯色** `--color-cta`，去掉 `#4f86ff → #2f6bff` 渐变。
- 未读角标：`--color-unread`，数字白字，最大 `99+`。

### 3.4 输入（发布页）

- 标题单行 `72rpx` 高；正文 `min-height 260rpx`。
- 计数器右下 `--font-caption` + `--color-text-tertiary`。
- 错误条：发布页已有 `error-text`，统一为 `--color-danger`。

---

## 4. 与现有实现的对照（不改架构）

| 模块 | 路径 | 设计系统衔接点 |
|------|------|----------------|
| 全局变量 | `miniprogram/app.wxss` | 替换 `--color-brand`、`--color-bg` 等 |
| 首页 | `pages/home/index` | 吸顶分类 + `content-card`；作者信息在标题下 |
| 关注 | `pages/follow/index` | `content-type-tabs` + 作者行外露 |
| 发布 | `pages/publish/index` | 非 Tab 页，卡片分节表单 |
| 消息 | `pages/notification/index` | 列表卡 + 未读左边条 |
| 我的 | `pages/mine/index` | `profile-header` + `entry-grid` / `entry-list-item` |
| TabBar | `custom-tab-bar/index` | 5 入口逻辑：4 Tab + 中间发布 |

**信息架构保持不变：** 首页推荐流、关注流、发布（navigateTo）、消息（通知中心）、我的。

---

## 5. 图标

- 延续现状：**CSS 几何自绘** Tab 图标（非 emoji），统一线宽 `3rpx`，颜色 `currentColor`。
- 禁止：emoji、随机混用多套 icon font。
- 若后续引入图标库：推荐 **Lucide** 风格线性图标，24×24 逻辑尺寸，与 `--color-text-secondary` 默认色一致。

---

## 6. 无障碍与性能

- 正文对比度 ≥ 4.5:1（`#1A1D21` on `#F7F5F0` / `#FFFFFF` 均满足）。
- 不仅用颜色区分状态：未读保留**左边条 + 「未读」文案**。
- 下拉刷新：仅信息流页启用（首页/关注/消息已启用），符合 Pro-Max「勿滥用」建议。
- 图片：列表 `lazy-load`（已用）；骨架屏首屏占位。

---

## 7. 前端落地优先级（全局层）

1. **P0** — 更新 `app.wxss` Token + `custom-tab-bar` 去蓝渐变（视觉立竿见影）。
2. **P0** — `state-block` / `page-list-footer` / 错误色统一为 Token。
3. **P1** — `content-card`、`content-type-tabs`、首页分类条同步品牌色。
4. **P1** — 我的页顶区渐变改为 `--color-accent-warm-light` → `--color-bg`。
5. **P2** — 卡片描边与阴影策略二选一（推荐描边为主）。
6. **P1（已确认）** — 首页完整搜索框入口、发布页固定提交栏、消息页一键全部已读、我的页资料完整度卡片；规格见 `pages/home.md`、`pages/publish.md`、`pages/notification.md`、`pages/mine.md` 与 `implementation-priority.md`。

---

## 8. 下一步（页面级设计稿）

在 MASTER 基础上，为以下页面编写 `design-system/校友社区/pages/*.md`（或设计文档分节）：

1. `home.md` — 首页  
2. `follow.md` — 关注  
3. `publish.md` — 发布  
4. `notification.md` — 消息  
5. `mine.md` — 我的  

每页包含：区块划分、栅格、组件规格、色彩语义、字体图标、交互、3–5 处改造点与收益。

---

## 附录：Pro-Max 原始命中存档

<details>
<summary>首次 --design-system 原始输出（未直接采用色板/字体）</summary>

- Pattern: Community/Forum Landing  
- Style: Vibrant & Block-based  
- Colors: Primary `#E11D48`, CTA `#2563EB`, Background `#FFF1F2`  
- Typography: Baloo 2 + Comic Neue  
- Anti-patterns: Heavy skeuomorphism, a11y ignored  

</details>
