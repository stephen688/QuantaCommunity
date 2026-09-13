# 一级页面 · 第三阶段增量美化

> **范围：** `pages/home/index`、`pages/follow/index`、`pages/publish/index`、`pages/notification/index`、`pages/mine/index`、`custom-tab-bar/index`。  
> **本文件只写第三阶段新增项**（配色 token 扫尾 + 图标/统计/次要面去绿化），不重做信息架构。  
> **关联：** `MASTER-phase3-token-delta.md`、`phase3-overview.md`、`primary-pages-polish.md`（二阶段补强基线）、`implementation-priority-phase2.md`（P0 验收项勿重复落地）。

---

## 1. 文档边界

### 1.1 二阶段已纳入、本阶段不重复论证

以下若在 `implementation-priority-phase2.md` §2 P0 已验收或已编码，**不再**作为 Phase3 默认任务重复列出：

| 类别 | 二阶段 P0 项（示例） |
|------|----------------------|
| 列表尾部 | 首页/关注接入或对齐 `page-list-footer` |
| 触摸态 | `content-type-tabs`、`custom-tab-bar`、消息「全部已读」`hover-class` |
| 内容卡 | 描边、`#F0EDE7` 占位、赞/藏/评 CSS 线性图标 |
| 骨架/空态 | `feed-skeleton` 暖色、`state-block` 场景变体（公告/信封等） |
| 发布页 | 固定底栏、计数 warning、提交钮墨绿三态 |

Phase3 在一级页的工作 = **在以上基础上应用 token-delta**，并补齐二阶段 P1/P2 中与「满眼绿」相关的残留项。

### 1.2 全局 token 引用（实现必读）

所有一级页色彩调整以 [`MASTER-phase3-token-delta.md`](MASTER-phase3-token-delta.md) §3 语义映射为准，禁止单页写死 `#1E5A4C` 作统计、链接、收藏激活色。

| 场景 | Phase3 token |
|------|----------------|
| 分类/筛选未选中 Chip | `--color-chip-neutral-bg` + `--color-chip-neutral-text` |
| 分类/Tab 选中 | `--color-brand-light` + `--color-brand`（保留） |
| 列表统计（赞/藏/评默认） | `--color-stat` |
| 用户已点赞统计 | `--color-stat-active` |
| 内链、展开、重试 | `--color-link` |
| 收藏已选（若一级页出现） | `--color-favorite-active` |
| 空状态装饰底（非 CTA 区） | 默认 neutral，仅带主操作的空态可小面积 `brand-light` |

---

## 2. 首页（`pages/home/index`）

| # | 第三阶段优化项 | 规格要点 | 依据 | 预期收益 |
|---|----------------|----------|------|----------|
| 1 | **分类激活态去「纯变绿」** | 未选中：图标+文案 `--color-text-secondary`，无底；激活：图标区 `52rpx` 圆底 `--color-chip-neutral-bg` 或 `--color-brand-light`（二选一，与 `content-type-tabs` 统一），文案/图标色 `--color-brand`；**禁止**未选中也用浅绿底 | **项目取舍** + **token-delta** | 首屏分类栏不再三块浅绿并列 |
| 2 | **搜索框 hover 层次** | 默认：底 `--color-chip-neutral-bg`、描边 `--color-border`；hover：底略深或 `--color-brand-light`，放大镜描边 `--color-brand`，占位符 `rgba(30,90,76,0.55)`；不用 scale | **Pro-Max** 触摸即时反馈；**MASTER 延续** | 搜索入口可点性更明确，仍非 CTA 绿块 |
| 3 | **内容卡统计色 token 化** | `content-card` 统计图标/数字：默认 `--color-stat`；已点赞项 `--color-stat-active`；收藏数**不用** brand 绿 | **token-delta** §5 | 信息流卡片底部不再「绿字+绿图标」 |
| 4 | **列表尾部文案色** | `page-list-footer` 失败链 `--color-link`；「没有更多了」保持 `--color-text-tertiary`；重试区最小高度 `64rpx` | **token-delta** + **二阶段延续** | 与二级列表页脚语义一致，失败链与主按钮可区分 |
| 5 | **空状态装饰中性化** | `state-block` 在首页 `type="empty"`：图形底圆/公告牌用 `--color-chip-neutral-bg`，主 CTA 仍 `--color-cta` | **项目取舍** | 空态不再大圆浅绿底抢主 CTA |

**前端衔接：** `pages/home/index.wxss`、`components/content-card/index.wxss`、`components/state-block/index.wxss`、`components/page-list-footer/index.wxss`。

---

## 3. 关注页（`pages/follow/index`）

| # | 第三阶段优化项 | 规格要点 | 依据 | 预期收益 |
|---|----------------|----------|------|----------|
| 1 | **`content-type-tabs` 与首页分类 token 对齐** | 未选中字/线 `--color-text-secondary`；激活下划线 `--color-brand`；未选中 tab **无** `brand-muted` 底；hover `opacity: 0.85` | **token-delta** | 关注流顶部与首页分类视觉语言一致 |
| 2 | **作者行关系胶囊（P2 可选）** | 若传入 `relationText="已关注"`：胶囊底 `--color-chip-neutral-bg`，字 `--color-chip-neutral-text`，`22rpx` 字号；**不用** success 绿 | **项目取舍** | 关系流识别更清晰，不增加接口 |
| 3 | **内容卡与统计色** | 同首页 §2 第 3 项；作者行昵称链进详情用 `--color-link` 仅当为次要链（可选 P2） | **token-delta** | 关注列表去绿化 |
| 4 | **空关注态图形 + 中性底** | `state-block` 双头像+加号变体；装饰 `--color-chip-neutral-bg`；CTA「去发现校友」仍 `--color-cta` | **二阶段 P1 延续** + **token-delta** | 冷启动有场景感，非绿球空态 |
| 5 | **页脚与首页一致** | 复用 `page-list-footer`；加载中 spinner/文案 `--color-text-tertiary` | **二阶段 P0 延续** | 一级双列信息流体验统一 |

**前端衔接：** `pages/follow/index.*`、`components/content-type-tabs/index.*`、`components/author-row/index.*`（P2）。

---

## 4. 发布页（`pages/publish/index`）

| # | 第三阶段优化项 | 规格要点 | 依据 | 预期收益 |
|---|----------------|----------|------|----------|
| 1 | **生活/专业 Chip 未选中中性化** | 未选中：`--color-chip-neutral-bg` + `--color-chip-neutral-text`；选中：生活仍 `--color-content-life-*`，专业底改 `--color-content-pro-bg`（暖灰褐 `#EDEAE4`）+ 字 `#1E5A4C` | **token-delta** §2.2 | 类型选择区不再两枚浅绿 chip 并排 |
| 2 | **分节标题线性图标（P1）** | 「内容类型/标题/正文/图片」前 `24rpx` CSS 图标，色 `--color-text-secondary`，线宽 `2.5rpx` | **二阶段 P1 延续** | 表单扫描更快，图标非品牌绿 |
| 3 | **字段错误与链接色** | 字段下 `field-error` 仍 `--color-danger`；卡片内「查看示例」类次要链 `--color-link` | **token-delta** | 错误与引导链层级清楚 |
| 4 | **计数器与 warning** | 默认 `--color-text-tertiary`；≥80% `--color-warning`（暖琥珀，非绿） | **MASTER 延续** | 临界态不用 success 绿表达 |
| 5 | **固定提交栏** | 按钮仅 `--color-cta`；栏顶 `1rpx var(--color-border)`；上传提示 `--color-text-secondary` | **MASTER 延续** | 提交区保持唯一主色块 |

**前端衔接：** `pages/publish/index.*`、`components/media-uploader/index.*`（上传格失败态 danger，非 brand）。

---

## 5. 消息页（`pages/notification/index`）

| # | 第三阶段优化项 | 规格要点 | 依据 | 预期收益 |
|---|----------------|----------|------|----------|
| 1 | **通知类型语义点/图标** | 系统通知：公告牌 `20rpx` 线性图标 `--color-text-secondary`；互动：对话气泡；默认：圆点 `--color-stat`；**不用** brand 填色块 | **二阶段 P1 延续** | 类型可扫读，非满屏绿字 |
| 2 | **未读左边条与胶囊** | 左边条 `--color-unread`；「未读」胶囊底 `rgba(196,61,61,0.1)` 字 `--color-unread`；已读卡 hover 仅白卡 `opacity: 0.96` | **MASTER 延续** | 未读用红系，不与 Tab 绿竞争 |
| 3 | **全部已读按钮** | 文案/描边 `--color-link`；hover `opacity: 0.85`；处理中 `disabled` + `--color-text-disabled` | **token-delta** + **二阶段 P0** | 工具操作与主 CTA 区分 |
| 4 | **空消息 `state-block`** | 信封/铃铛 CSS 变体 + `--color-chip-neutral-bg` 装饰底 | **二阶段 P1** | 空态有语境 |
| 5 | **列表骨架色** | `feed-skeleton variant="notification"`  shimmer 接近 `#F0EDE7` / `#EDEAE4`，避免 `#E8F2EF` 大面积 | **token-delta** | 加载态不偏绿 |

**前端衔接：** `pages/notification/index.*`、`components/state-block/index.*`、`components/page-list-footer/index.*`。

---

## 6. 我的页（`pages/mine/index`）

| # | 第三阶段优化项 | 规格要点 | 依据 | 预期收益 |
|---|----------------|----------|------|----------|
| 1 | **入口网格图标底中性化** | `entry-grid` / `entry-list-item`：图标容器底 `--color-chip-neutral-bg`，图标线 `--color-text-secondary`；按 key 绘 CSS 图标（发布/点赞/收藏/历史/认证/通知）线宽 `2.5rpx` 统一 | **token-delta** §5 + **二阶段 P1** | 去掉无语义暖绿渐变块 |
| 2 | **资料完整度卡片** | 进度条轨道 `--color-chip-neutral-bg`；填充 `--color-accent-warm` 或 `--color-brand`（仅进度，二选一全局统一）；hint `--color-text-secondary` | **项目取舍** | 完整度引导偏暖/中性，非整块浅绿卡 |
| 3 | **认证徽章** | 已通过：`--color-success` 字 + 浅底 `rgba(42,107,71,0.12)`；未认证 neutral 胶囊；圆角 `999rpx` 与消息胶囊统一 | **token-delta** success 分离 | 认证与 Tab 激活绿可区分 |
| 4 | **顶部渐变收敛** | Hero 渐变终点 `--color-card-bg`；点缀仅边框或统计数字用 `--color-accent-warm`，**不**在渐变中铺 `brand-light` | **项目取舍** | 我的页头部层次更暖、更少绿 |
| 5 | **触摸态统一** | grid / list / 完整度卡 hover 统一 `opacity: 0.94–0.95` | **Pro-Max** | 交互一致 |

**前端衔接：** `pages/mine/index.*`、`components/entry-grid/index.*`、`components/entry-list-item/index.*`、`components/profile-header/index.*`。

---

## 7. 自定义 TabBar（`custom-tab-bar/index`）

| # | 第三阶段优化项 | 规格要点 | 依据 | 预期收益 |
|---|----------------|----------|------|----------|
| 1 | **选中态保留 brand，未选 tertiary** | 图标/文案：未选 `--color-text-tertiary`；选中 `--color-brand` + `font-weight: 600` | **MASTER 延续** | 主导航识别不变 |
| 2 | **激活非颜色辅助（P2）** | 选中项下方 `28rpx × 4rpx` 条，色 `rgba(30,90,76,0.35)` 或 `--color-brand-muted`；发布 FAB **不加** 下划线 | **Pro-Max** 当前态；**二阶段 P2** | 弱视觉辅助，真机验证不过重 |
| 3 | **发布 FAB** | 背景 `--color-cta`；阴影 `rgba(30,90,76,0.25)`；hover `opacity: 0.9`；**禁止** scale | **MASTER 延续** | 中间入口仍是一级主 CTA |
| 4 | **未读角标** | `--color-unread`；与消息页一致 `99+` 规则 | **MASTER 延续** | 角标不抢 Tab 绿 |
| 5 | **安全区复验** | `padding-bottom: env(safe-area-inset-bottom)`；全面屏 FAB 不贴边、不过高 | **Pro-Max** sticky/safe-area | 与详情底栏 Phase3 规范一致 |

**前端衔接：** `custom-tab-bar/index.*`；不改 `app.json` pagePath。

---

## 8. 推荐落地批次（一级 Phase3）

| 优先级 | 内容 |
|--------|------|
| **P0** | `app.wxss` token-delta；`content-card` 统计色；`content-type-tabs` / 首页分类 neutral 未选中 |
| **P1** | 首页搜索 hover；我的入口图标；消息类型图标；发布 Chip token；TabBar 激活辅助（可选） |
| **P2** | 关注作者「已关注」胶囊；完整度进度暖色；分类激活圆底 A/B |

---

## 9. 验收清单（一级 Phase3）

- [ ] 首页首屏分类 + 搜索 + 首张卡统计**不同时**出现三块以上 brand/brand-light 绿面。
- [ ] 主 CTA（发布 FAB、去发布、提交）仍为 `--color-cta` / `--color-brand`，一眼可认。
- [ ] 统计、次要链、全部已读等使用 `--color-stat` / `--color-link`，与主按钮可区分。
- [ ] 未引入电蓝、社区紫、玫红主色；无 emoji 图标；hover 无 scale。
- [ ] 与 `implementation-priority-phase2.md` P0 项无冲突（页脚、基础 hover、卡片描边仍有效）。
- [ ] 真机：TabBar 安全区、首页/关注加载尾、我的入口可点区域 ≥ `88rpx` 高（按 Pro-Max 触控目标换算）。

---

## 10. 溯源索引

| 文件 | 用途 |
|------|------|
| `MASTER-phase3-token-delta.md` | 配色 delta 与全局落点 |
| `phase3-overview.md` §3 | Pro-Max 检索采纳/不采纳 |
| `primary-pages-polish.md` | 二阶段逐项缺口（实现时对照是否已完成） |
| `implementation-priority-phase3.md` | 与二级、评论组件合并排期（待产出或已产出） |
