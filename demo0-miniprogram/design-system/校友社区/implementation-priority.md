# 前端落地优先级建议

> 原则：先改全局 token 和共享组件，再改页面样式。这样改动少、收益大，也能避免五个页面各自散落重复样式。

## P0：全局视觉统一

### 1. 更新 `miniprogram/app.wxss` token

**目标：** 用校友社区品牌色替换现有电蓝体系。

建议新增或替换：

```css
page {
  --color-bg: #F7F5F0;
  --color-card-bg: #FFFFFF;
  --color-surface-elevated: #FFFFFF;
  --color-text-primary: #1A1D21;
  --color-text-secondary: #5C6370;
  --color-text-tertiary: #8C939E;
  --color-text-disabled: #B8BFC8;
  --color-brand: #1E5A4C;
  --color-brand-light: #E8F2EF;
  --color-brand-muted: rgba(30, 90, 76, 0.12);
  --color-accent-warm: #B45309;
  --color-accent-warm-light: #FDF6EC;
  --color-cta: #1E5A4C;
  --color-border: rgba(26, 29, 33, 0.08);
  --color-divider: rgba(26, 29, 33, 0.06);
  --color-success: #2D7A4F;
  --color-warning: #B45309;
  --color-danger: #C43D3D;
  --color-unread: #C43D3D;
}
```

**验收：**

- 首页、关注、消息、我的不再出现 `#2f6bff` 作为主视觉。
- 页面背景统一为暖纸底。
- 主按钮与激活态为深墨绿。

### 2. 更新 `custom-tab-bar`

**目标：** 去掉中间发布按钮蓝紫渐变，统一 tab 激活态。

涉及：

- `custom-tab-bar/index.wxss`
- `tabbar__item--active`
- `tabbar__publish`
- `tabbar__badge`

**验收：**

- 发布按钮为纯 `--color-cta`，阴影为 `rgba(30, 90, 76, 0.22)`。
- tab 激活图标与文字为 `--color-brand`。
- 未读角标使用 `--color-unread`。

### 3. 更新 `state-block`

**目标：** 空状态、错误状态与品牌一致。

涉及：

- `components/state-block/index.wxss`

**验收：**

- 状态图形不再使用蓝灰渐变。
- CTA 边框和文字为 `--color-brand`。
- 错误状态可通过传入类型或页面文案表达，不只靠颜色。

## P1：共享组件统一

### 4. 更新 `content-card`

**目标：** 统一信息流卡片，适配首页和关注。

涉及：

- `components/content-card/index.wxss`

建议：

- 卡片加 `1rpx solid var(--color-border)`。
- 图片占位底色改为 `#F0EDE7`。
- `.cc__answer` 使用 `--color-brand`。
- `.cc__stats` 顶部边线使用 `--color-divider`。

**验收：**

- 首页和关注卡片边界清晰但不厚重。
- 生活/专业内容不再出现强蓝强调。

### 5. 更新 `content-type-tabs`

**目标：** 关注页筛选 tabs 与首页分类保持同一品牌。

涉及：

- `components/content-type-tabs/index.wxss`

**验收：**

- 下划线为 `--color-brand`。
- 底部分割线为 `--color-divider`。
- 激活文字不过度依赖蓝色。

### 6. 更新 `entry-grid` 与 `entry-list-item`

**目标：** 我的页入口组件去蓝化，增加校园温度。

涉及：

- `components/entry-grid/index.wxss`
- `components/entry-list-item/index.wxss`

**验收：**

- 图标占位从蓝渐变改为暖绿/砖赭浅渐变。
- 列表项有细描边，阴影更弱。
- 未读 badge 语义清晰。

### 7. 更新 `profile-header`

**目标：** 强化身份可信感。

涉及：

- `components/profile-header/index.wxss`

**验收：**

- 头像边框为墨绿浅色。
- 认证徽章使用 success/warning/danger/neutral token。
- 统计背景不再使用蓝色渐变。

## P1：页面样式微调

### 8. 首页 `pages/home/index.wxss`

**目标：** 吸顶分类栏与推荐流统一。

**验收：**

- 吸顶栏分割线使用 `--color-divider`。
- 分类激活态使用 `--color-brand`。
- 右侧搜索入口按 `pages/home.md`「搜索框入口」升级为完整横向搜索框（宽 `188-220rpx`），点击进入 `ROUTES.SEARCH`，有明确触摸反馈。

### 9. 关注页 `pages/follow/index.wxss`

**目标：** 标题区更稳，关注流更像关系流。

**验收：**

- 标题使用 `40rpx / 600` 或保持 `36rpx` 但留白更舒展。
- 加载更多、结束态颜色 token 化。

### 10. 发布页 `pages/publish/index.wxss`

**目标：** 表单清晰、CTA 统一、错误醒目。

**验收：**

- `type-chip--active` 不再使用蓝色。
- 提交按钮使用 `--color-cta`。
- 错误色使用 `--color-danger`。
- 卡片增加细描边。
- 底部固定提交栏与页面底部留白按 `pages/publish.md`（含安全区、`submitting/uploading` 态）。

### 11. 消息页 `pages/notification/index.wxss`

**目标：** 未读状态更明确。

**验收：**

- 未读左边条 token 化。
- 「未读」从纯文字变为轻量胶囊。
- 通知正文可限制最大行数，避免长卡片。
- 列表顶部「全部已读」工具栏：仅存在未读时可点，批量已读后刷新列表并同步 TabBar 未读（见 `pages/notification.md`）。

### 12. 我的页 `pages/mine/index.wxss`

**目标：** 身份页去蓝化，形成温暖头部。

**验收：**

- 顶部渐变使用 `--color-accent-warm-light` 到 `--color-bg`。
- 常用入口和功能列表与 `profile-header` 风格一致。
- `profile-header` 与常用入口之间增加资料完整度卡片（进度与引导，见 `pages/mine.md`）。

## P2：体验细节增强

### 13. Skeleton 适配

**目标：** 不同页面加载态与真实内容更接近。

建议：

- 首页：卡片骨架。
- 关注：作者行 + 卡片骨架。
- 消息：通知列表骨架，不复用首页 variant。
- 我的：个人头部骨架已存在，统一颜色。

### 14. 计数器临界状态

**目标：** 发布页输入上限提前提醒。

建议：

- 标题 `>=45/50` 时计数器使用 `--color-warning`。
- 正文 `>=460/500` 时计数器使用 `--color-warning`。

### 15. Motion Reduced

**目标：** 尊重低动效偏好。

建议：

- 骨架屏动画可在后续通过 class 或全局约定关闭。
- 所有 hover 不用 scale。

## 推荐实施批次

### Batch 1：全局去蓝与共享状态

- `app.wxss`
- `custom-tab-bar/index.wxss`
- `state-block/index.wxss`

**预计收益：** 主视觉立即统一。

### Batch 2：信息流与发布页

- `content-card/index.wxss`
- `content-type-tabs/index.wxss`
- `pages/home/index.wxss`
- `pages/follow/index.wxss`
- `pages/publish/index.wxss`

**预计收益：** 首页、关注、发布三个高频页面视觉完成度提升。

### Batch 3：消息与我的

- `pages/notification/index.wxss`
- `pages/mine/index.wxss`
- `profile-header/index.wxss`
- `entry-grid/index.wxss`
- `entry-list-item/index.wxss`

**预计收益：** 账户身份和通知状态更可信、更清晰。

## 最终验收清单

- [ ] 全局主色不再使用 `#2f6bff`。
- [ ] 发布 FAB 和发布页按钮不再使用蓝紫渐变。
- [ ] 首页、关注、消息列表卡片有统一边界策略。
- [ ] 空状态有说明；可行动场景有单一 CTA。
- [ ] 未读状态有形态 + 文案双重表达。
- [ ] 认证状态使用 success/warning/danger/neutral token。
- [ ] 图片、头像、图标占位色不再是蓝灰模板感。
- [ ] 所有触摸反馈不造成布局跳动。
- [ ] 底部安全区未遮挡列表内容。
- [ ] 首页分类栏右侧为完整搜索框入口并按稿跳转搜索页。
- [ ] 发布页提交区固定底部且不遮挡表单与安全区。
- [ ] 消息页支持在有未读时「全部已读」并与 TabBar 未读同步。
- [ ] 我的页在个人头部下展示资料完整度引导卡片。
- [ ] 页面仍保持原有功能与信息架构。
