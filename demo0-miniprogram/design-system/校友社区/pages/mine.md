# 我的页设计稿

> 对应实现：`miniprogram/pages/mine/index.*`。  
> 保持现有「个人头部 + 资料完整度引导 + 常用入口 grid + 功能列表」结构。

## 1. 页面目标

我的页是用户身份与资产中心。它要突出「我是这个校友社区中的谁」：头像、认证状态、学院/批次、内容资产，以及常用入口。整体应比信息流更稳定、更有归属感。

## 2. 信息区块

1. **个人头部 `profile-header`**
   - 现有：加载/错误/用户信息/认证徽章/身份行/统计/编辑。
   - 职责：展示身份可信度与个人资料完整感。
   - 设计要求：头像和认证状态是首要信息，统计是次要信息。

2. **资料完整度卡片（已确认纳入）**
   - 位置：`profile-header` 下方、**常用入口**之上，与 `.mine__section` 左右边距一致（如 `8rpx 24rpx 0`）。
   - 职责：展示资料填写进度，引导实名认证与资料补全；**不改变常用入口排序与数量**。
   - 设计要求：鼓励性、低压力；具体计分规则由接口或前端约定，需在实现里写清字段清单避免争议。

3. **常用入口**
   - 现有：`entry-grid`，我的发布、我的点赞、我的收藏、浏览历史。
   - 职责：内容资产快捷入口。
   - 设计要求：图标占位从蓝渐变改为暖绿/暖灰块面，减少模板感。

4. **功能列表**
   - 现有：实名认证、通知中心。
   - 职责：账户能力与系统入口。
   - 设计要求：列表项应像设置入口，行高稳定，角标清楚。

5. **底部安全区**
   - 保留 `mine__safe-bottom`。

## 3. 布局与栅格

- 页面背景：顶部从 `--color-accent-warm-light` 渐变到 `--color-bg`，高度约 `220rpx`，保持现有渐变结构但去电蓝。
- 头部外边距：`16rpx 24rpx 0` 保持。
- 资料完整度卡片：白底卡片与头部区隔 `12-16rpx`；整卡可点区域高度适中，勿高于身份区视觉权重。
- 区块：`.mine__section` 使用 `padding: 8rpx 24rpx 0`。
- 常用入口：2 列 grid 保持，每格宽 `50%`。
- 功能列表：纵向 `gap: 16rpx`。
- 底部：`calc(120rpx + env(safe-area-inset-bottom))`。

## 4. 组件规格

### 个人头部

- 容器：建议白底卡片化，圆角 `20rpx`，padding 保持 `28rpx 24rpx 24rpx`。
- 背景：`--color-card-bg`。
- 边框：`1rpx solid rgba(180,83,9,0.10)` 或 `--color-border`。
- 阴影：可使用 `--shadow-card`，只用于头部，不扩散到所有卡片。

### 头像

- 尺寸：`120rpx × 120rpx` 保持。
- 圆角：50%。
- 边框：`2rpx solid rgba(30,90,76,0.12)`，替换蓝色。
- 占位：浅暖灰或浅绿渐变，不用蓝灰渐变。

### 昵称与编辑

- 昵称：`36rpx / 600 / --color-text-primary`。
- 编辑按钮：胶囊，`8rpx 20rpx`，边框 `--color-border`，背景 `#FFFFFF` 或 `#F7F5F0`。
- 编辑文字：`24rpx / --color-text-secondary`。

### 认证徽章

- 成功：文字 `--color-success`，底 `rgba(45,122,79,0.10)`。
- 审核中：文字 `--color-warning`，底 `rgba(180,83,9,0.10)`。
- 不通过：文字 `--color-danger`，底 `rgba(196,61,61,0.08)`。
- 未认证：文字 `--color-text-secondary`，底 `#F0EDE7`。
- 圆角：`8rpx` 保持，但可提升到 `999rpx` 形成 badge。

### 身份行

- 字号：`24rpx`。
- 颜色：`--color-text-secondary`。
- 内容：身份类型 + 院系/批次，不新增字段。
- 长文本：允许换行，行高 1.45。

### 统计区

- 布局：三等分，保留分隔线。
- 背景：从蓝色浅渐变改为 `linear-gradient(180deg, rgba(30,90,76,0.06), rgba(30,90,76,0.02))`。
- 数字：`32rpx / 600 / --color-text-primary`。
- 标签：`22rpx / --color-text-tertiary`。

### 资料完整度卡片

- 容器：白底 `--color-card-bg`，圆角 `16-20rpx`，padding `20-24rpx`，`1rpx solid var(--color-border)`，与 `profile-header` 风格协调。
- 标题行：如「资料完整度」或「完善资料」，`26-28rpx / 600`，`--color-text-primary`；右侧可选弱文案「去完善」`--color-brand`。
- 进度条：轨道高 `12-16rpx`，圆角 `999rpx`；轨道 `#F0EDE7` 或 `--color-brand-muted`；填充 `--color-brand` 或自 `--color-brand-light` 至 `--color-brand` 的短渐变（保持克制）。
- 说明：一行 `22-24rpx`，`--color-text-tertiary`，例如「完善后校友更容易认出你」；未实名时可提示认证，与认证徽章语义一致、不重复恐吓。
- 百分比或「已填 n/m 项」二选一展示，与后端字段对齐；加载失败可隐藏卡片或展示占位，不阻断整页。

### 常用入口 Grid

- 每格：`50%` 宽，padding `18rpx 10rpx`。
- 图标块：`88rpx × 88rpx`，圆角 `22rpx`。
- 占位背景：`linear-gradient(145deg, rgba(30,90,76,0.10), rgba(180,83,9,0.06))`。
- 标题：`28rpx / 600 / --color-text-primary`。
- 副标题：`22rpx / --color-text-tertiary`，目前为空可不显示。
- 触摸：opacity `0.94`。

### 功能列表项

- 行容器：白底，圆角 `16rpx`，padding `26rpx 24rpx`。
- 边框：`1rpx solid --color-border`。
- 阴影：去掉或仅保留 `0 2rpx 12rpx rgba(26,29,33,0.04)`。
- 图标块：`72rpx × 72rpx`，圆角 `18rpx`，使用同 grid 的色系。
- 标题：`30rpx / 600`。
- 副文案：`24rpx / --color-text-tertiary`。
- 箭头：`#B8BFC8` 保持。
- Badge：通知中心未读使用 `--color-unread` 胶囊或点状数字。

## 5. 色彩与语义

- 页面顶部渐变：`--color-accent-warm-light` → `--color-bg`。
- 头像/统计/占位图标：使用 `--color-brand` 浅色体系。
- 认证成功：`--color-success`。
- 审核中/校友身份点缀：`--color-warning` / `--color-accent-warm`。
- 未认证：中性色，避免制造失败感。
- 通知未读：`--color-unread`。

## 6. 字体与图标

- 昵称最大字号：`36rpx`，不超过页面视觉主标题。
- 区块标题「常用入口」：`26rpx / 600 / --color-text-secondary`，保持弱化。
- 入口图标：推荐继续使用占位块或线性图标，禁止 emoji。
- 若后续补图标，四个资产入口使用同一线宽、同一圆角风格。

## 7. 关键交互

- 进入页面：加载资料；`profile-header` 展示骨架。
- 资料失败：展示错误标题/说明/重试按钮。
- 点击编辑：进入资料编辑。
- 点击资料完整度卡片：进入资料编辑；若业务约定未认证优先，可带 `PROFILE_EDIT?intent=verify` 或与「编辑资料」同一路由。
- 点击常用入口：按 route 进入发布、点赞、收藏、历史。
- 点击实名认证：进入 `PROFILE_EDIT?intent=verify`。
- 点击通知中心：进入消息页。
- onShow：同步 tabBar 选中与未读数，现有逻辑保留。

## 8. 与现有实现衔接

1. **顶部渐变去蓝**
   - `.mine` 从 `rgba(47,107,255,0.07)` 改为 `--color-accent-warm-light` / 墨绿浅色。
   - 收益：从 SaaS 蓝切换到校园暖感。

2. **`profile-header` 头像边框和统计背景 token 化**
   - 替换蓝色 rgba。
   - 收益：身份区域成为品牌核心。

3. **认证徽章使用状态色 token**
   - 成功/警告/危险/中性统一。
   - 收益：审核状态更清晰。

4. **入口图标占位从蓝渐变改为暖绿/砖赭**
   - 涉及 `entry-grid`、`entry-list-item`。
   - 收益：入口区不再像通用模板。

5. **功能列表项加边框，弱化阴影**
   - 涉及 `.row`。
   - 收益：更克制，符合可信社区气质。

6. **资料完整度卡片**
   - 涉及：`pages/mine` 在 `profile-header` 与 `entry-grid` 之间新增区块、进度数据拉取与点击跳转。
   - 收益：引导补全资料与认证，且不打乱现有入口架构。

## 9. 不纳入默认设计稿的大改建议

- 不新增个人主页动态预览。
- 资料完整度进度条已确认纳入本轮；规则与字段清单见实现与接口约定。
- 不调整常用入口数量和排序。
