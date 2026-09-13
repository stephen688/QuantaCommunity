# 一级页面补强设计稿

> 范围：`pages/home/index`、`pages/follow/index`、`pages/publish/index`、`pages/notification/index`、`pages/mine/index`、`custom-tab-bar/index`。  
> 本文件只盘点一级五页与 TabBar 已完成后的缺失和可继续美化项，不改动现有功能与主信息架构。

---

## 1. 审计依据

### 1.1 继续沿用的既有依据

- **产品方提示词**：校友社区应体现社区感、归属感、可信与温暖；禁止泛 AI 电蓝渐变、玻璃拟态堆砌、无意义光晕；组件必须覆盖默认、触摸、禁用、加载、错误等状态。
- **`MASTER.md`**：深墨绿 `#1E5A4C`、暖纸底 `#F7F5F0`、白卡 + 细描边、CSS 几何图标、空状态单一行动、动效 150-300ms、触摸不使用 scale。
- **一级页面稿**：`pages/home.md`、`pages/follow.md`、`pages/publish.md`、`pages/notification.md`、`pages/mine.md` 中已确认的搜索框入口、固定发布栏、全部已读、资料完整度等继续有效。

### 1.2 本轮补充复核

本轮补充执行了 UI-UX-Pro-Max 检索：

```bash
python .../ui-ux-pro-max/scripts/search.py "alumni community primary pages tabbar polish mobile app" --design-system -p "校友社区"
```

命中再次偏向高饱和社区紫、下载页式营销结构和块面化视觉。本项目**不采纳**这些色板和 Landing 结构，只保留其中对「明确 CTA、真实界面状态、图标特征清晰、动效 200-300ms」的通用建议。一级页继续以 `MASTER.md` 的墨绿、暖纸底、克制卡片为准。

### 1.3 当前实现已完成基础

经对照小程序实现，第一阶段关键项已基本落地：

- 全局 token 已在 `miniprogram/app.wxss` 定义，页面背景、主色、状态色、分割线、低动效偏好均已接入。
- `custom-tab-bar` 已去掉蓝紫渐变，改为纯墨绿发布 FAB、CSS 几何图标、未读角标 `--color-unread`。
- 首页已使用完整横向搜索框入口、三分类 CSS 图标、`feed-skeleton`、`state-block`、列表尾部反馈。
- 发布页已使用分节卡片、语义类型 chip、计数临界 warning、固定底部提交栏、上传中禁用与错误摘要。
- 消息页已使用通知型 skeleton、全部已读工具栏、未读左边条 + 胶囊、正文三行截断、`page-list-footer`。
- 我的页已使用暖色顶部渐变、资料完整度卡片、`profile-header` 状态徽章、入口组件去蓝化。

---

## 2. 总体缺口与继续美化方向

### 2.1 共性缺口

| 缺口 | 当前表现 | 补强方式 | 前端衔接点 | 收益 |
|------|----------|----------|------------|------|
| 入口图标语义仍偏占位 | 我的页入口与功能列表多为渐变占位块；内容卡统计行用纯文字「赞/藏/评」 | 保持 CSS 几何/线性体系，为资产入口、统计项、上传入口补轻量图形 | `entry-grid`、`entry-list-item`、`content-card`、`media-uploader` | 页面更像完整产品，而不是未配图标的模板 |
| 触摸态覆盖不完全 | 部分可点区域有 `hover-class`，部分如 `content-type-tabs`、TabBar item、消息全部已读缺少显式 hover | 统一 `opacity: 0.85-0.96`，不位移、不缩放 | `content-type-tabs/index.wxml|wxss`、`custom-tab-bar/index.*`、`notification/index.wxss` | 降低交互割裂，符合 Pro-Max 触摸反馈要求 |
| 列表尾部反馈实现不统一 | 首页/关注仍为页面内文本，消息使用 `page-list-footer` | 首页、关注改用 `page-list-footer` 或抽象同规格样式 | `home/index.wxml`、`follow/index.wxml`、`page-list-footer` | 多个信息流结束/失败状态一致 |
| 空状态图形仍较抽象 | `state-block` 只是圆形渐变，不像公告、信封、内容空态 | 在不引入图标库的前提下增加 `type` 变体：公告牌、信封、空列表、网络错误 | `state-block/index.wxml|wxss` | 空/错状态更有场景感，仍不使用 emoji |
| 骨架屏还可更贴近真实组件 | 已有 home/follow/notification variant，但没有分类栏、发布表单、我的完整度骨架 | 对关键首屏补局部骨架：分类栏、表单卡片、资料完整度 | `feed-skeleton`、`publish/index.wxml`、`mine/index.wxml` | 加载时布局更稳定，减少跳变 |
| 状态文案可更校友化 | 多处为通用「暂无/加载失败/点此重试」 | 保持简短，但加入社区语境：找校友、去发布、稍后刷新 | 页面 `stateTitle` / `emptyDesc` / footer 文案 | 更有产品气质，不改变业务 |

### 2.2 不作为默认补强的事项

- 不新增首页 Banner、活动推荐、关注分组、私信入口、消息分类 tabs。
- 不调整 TabBar 信息架构，继续保持 4 个 tab + 中间发布入口。
- 不把一级页重构为营销首页或瀑布流。
- 不引入新的接口字段；涉及推荐、分组、批量管理等进入后续 `needs-confirm-phase2.md`。

---

## 3. 首页补强

### 3.1 当前已完成基础

- 吸顶栏已使用暖纸底、弱分割线、深墨绿激活态。
- `全部 / 生活 / 专业` 已有 CSS 几何图标，搜索入口已升级为横向搜索框并跳转现有搜索页。
- 首屏加载、错误、空状态均已覆盖；列表卡片由 `content-card` 统一承载。
- 内容卡已加细描边，图片占位为暖灰，作者行在标题下。

### 3.2 仍缺什么

1. **分类图标还缺少选中细节**
   - 现状：图标随 `currentColor` 变绿，但形态没有轻微层级变化。
   - 补法：激活态可为图标增加浅底圆片或局部填充，例如 `home__cat-picto` 加 `background: var(--color-brand-light)`，直径 `52rpx`，默认无底。
   - 注意：不要加发光或 scale；触摸仍只降透明度。

2. **搜索框触摸态可以更明确**
   - 现状：hover 只把背景改为 `--color-brand-light`。
   - 补法：hover 时同步让放大镜描边使用 `--color-brand`，占位文案变 `--color-brand` 的 70% 透明度。
   - 前端：`.home__search--hover .home__search-ico`、`.home__search--hover .home__search-handle`、`.home__search--hover .home__search-placeholder`。

3. **统计行仍是纯文字**
   - 现状：`赞 0 / 藏 0 / 评 0` 可读但视觉完成度弱。
   - 补法：`content-card` 为赞、藏、评补同一线性小图标，尺寸 `24rpx`，线宽 `2.5rpx`，颜色 `--color-text-tertiary`。
   - 不新增点击行为，只是视觉语义补强。

4. **列表尾部与关注页不够组件化**
   - 现状：首页使用 `.home__more-err`、`.home__end`。
   - 补法：改用 `page-list-footer` 或让样式完全对齐 `page-list-footer`。
   - 收益：一级信息流的加载失败、结束态一致。

5. **首页空状态图形可更像「公告栏」**
   - 现状：`state-block` 圆形渐变偏抽象。
   - 补法：为 `type="empty"` 增加公告牌/卡片线条伪元素，仍用 CSS 实现。
   - 收益：呼应「校友录与布告栏」品牌方向。

### 3.3 状态覆盖

- 默认：分类栏 + 搜索框 + 推荐流。
- 触摸：分类、搜索、卡片均 opacity 或浅底变化。
- 加载：`feed-skeleton variant="home"`；建议后续补分类栏骨架。
- 空：保留「去发布」单一主操作。
- 错误：`state-block` + 重试。
- 列表尾部：失败重试、没有更多、底部安全区。

### 3.4 前端衔接点

- `pages/home/index.wxml|wxss`
- `components/content-card/index.wxml|wxss`
- `components/state-block/index.wxml|wxss`
- `components/page-list-footer/index.*`

---

## 4. 关注页补强

### 4.1 当前已完成基础

- 页面标题已提升到 `40rpx / 600`，具备关系流识别。
- `content-type-tabs` 已去蓝，激活下划线为 `--color-brand`。
- `feed-skeleton variant="follow"` 已覆盖作者行骨架。
- 空状态按不同原因给出行动，内容卡显示作者行。

### 4.2 仍缺什么

1. **Tabs 缺少显式触摸态**
   - 现状：`tab` 可点但没有 `hover-class`。
   - 补法：为每个 tab 加 `hover-class="tab--hover"`，样式 `opacity: 0.85`。
   - 收益：与首页分类和按钮触摸反馈一致。

2. **关系流可增加轻量说明文案**
   - 现状：只有「关注动态」标题。
   - 补法：在标题下可选增加一行弱文案「看看你关注的校友最近在聊什么」，`24rpx / --color-text-tertiary`。
   - 边界：不增加新功能；如担心首屏拥挤可不实现，只保留设计备用。

3. **空关注引导图形不够具体**
   - 现状：复用通用 `state-block` 圆形图形。
   - 补法：`type="empty"` 根据文案场景显示「双头像 + 加号」CSS 图形。
   - 收益：冷启动更明确，不需要新增推荐关注模块。

4. **作者关系提示还可以更清楚**
   - 现状：作者行有头像/昵称/时间，但没有轻关系标识。
   - 补法：在 `author-row` 可选支持 `relationText="已关注"` 小胶囊，默认只在关注页传入。
   - 风险：如果当前数据模型没有明确关注状态，不作为 P0；可在设计稿标为 P1/P2。

5. **列表尾部建议复用组件**
   - 现状：与首页一样是页面内文本。
   - 补法：使用 `page-list-footer`，并保证加载失败可点区域不小于 `64rpx` 高。

### 4.3 状态覆盖

- 默认：标题 + 类型 tabs + 关注内容流。
- 触摸：tabs、卡片、空状态按钮。
- 加载：作者行 + 卡片骨架。
- 空：无关注内容、当前筛选无内容两类文案。
- 错误：重试按钮。
- 列表尾部：加载更多、失败、已结束。

### 4.4 前端衔接点

- `pages/follow/index.wxml|wxss`
- `components/content-type-tabs/index.wxml|wxss`
- `components/author-row/index.*`
- `components/page-list-footer/index.*`

---

## 5. 发布页补强

### 5.1 当前已完成基础

- 表单已分为内容类型、标题、正文、图片四个卡片。
- 生活/专业 chip 已使用弱语义色，提交中/上传中会弱化。
- 标题和正文计数器已在临界值变为 warning。
- 底部固定提交栏已落地，上传中显示提示，错误展示在按钮上方。
- 提交按钮纯墨绿，disabled/loading 状态已有区分。

### 5.2 仍缺什么

1. **分节标题缺少语义图标**
   - 现状：标题均为纯文字。
   - 补法：在「内容类型 / 标题 / 正文 / 图片」前加 `24rpx` CSS 线性图标：切换、标题线、段落线、图片框。
   - 收益：表单扫描更快，不改变字段。

2. **字段错误还停留在底部摘要**
   - 现状：错误集中在固定栏，字段本身没有红色说明。
   - 补法：在校验失败时可给对应卡片加 `publish-card--error`，标题下显示一行 `field-error`。
   - 边界：不强制自动滚动；只增加就地反馈。

3. **图片上传状态可更细**
   - 现状：由 `media-uploader` 承担，页面只知道 `uploading`。
   - 补法：上传格增加上传中遮罩、失败重试/删除、禁用 opacity，色彩使用 `--color-danger` 与 `--color-text-disabled`。
   - 前端：`components/media-uploader/index.*`。

4. **固定栏和键盘关系需要验收**
   - 现状：样式已固定底部。
   - 补法：实现验收时检查标题、正文聚焦、图片区域滚动到底部时是否被栏遮挡；必要时使用 `scroll-into-view` 或聚焦时临时收敛提示文案。
   - 收益：避免长正文场景提交栏挡内容。

5. **发布成功后的视觉反馈可更温暖**
   - 现状：系统 toast「发布成功」。
   - 补法：toast 文案可为「发布成功，已进入社区」；仍使用系统 toast，不做新页面。

### 5.3 状态覆盖

- 默认：四个表单卡片 + 固定提交栏。
- 触摸：类型 chip、上传格、提交按钮。
- 加载：提交按钮 loading；上传中显示提示并禁用提交。
- 错误：底部摘要 + 建议字段附近错误。
- 禁用：上传中/提交中 chip 与按钮不可操作。
- 成功：toast + 跳转/返回现有逻辑。

### 5.4 前端衔接点

- `pages/publish/index.wxml|wxss|ts`
- `components/media-uploader/index.*`
- 若新增字段错误，需要在页面 data 中区分 `titleError`、`contentError`、`imageError`，不需要后端字段。

---

## 6. 消息页补强

### 6.1 当前已完成基础

- 首屏已使用 `feed-skeleton variant="notification"`，不再复用首页骨架。
- 列表顶部已在有未读时显示「全部已读」，处理时 loading/disabled。
- 未读状态已由左边条 + 胶囊文案双重表达。
- 正文已三行截断，列表底部使用 `page-list-footer`。
- TabBar 角标已使用 `--color-unread` 并支持 `99+`。

### 6.2 仍缺什么

1. **消息类型缺少图标或色点**
   - 现状：`typeDesc` 只有文字。
   - 补法：在类型前加 `20rpx` 语义点或 CSS 线性图标。系统通知用公告牌，互动通知用对话气泡，默认通知用圆点。
   - 边界：若类型枚举不稳定，先只做统一小圆点，不做复杂映射。

2. **全部已读按钮触摸态未完全接入**
   - 现状：wxss 有 `.nt__mark-all--hover`，模板未声明 `hover-class`。
   - 补法：button 增加 `hover-class="nt__mark-all--hover"`。
   - 收益：操作状态闭环。

3. **空消息状态没有行动但图形可更贴切**
   - 现状：空状态无按钮是合理的，但图形仍通用。
   - 补法：`state-block` 在消息页可使用信封/铃铛形态。
   - 收益：没有通知时也保持完整度。

4. **批量已读成功反馈可更轻**
   - 现状：取决于实现逻辑。
   - 补法：成功后 toast「已全部标为已读」，失败 toast「操作失败，请重试」；列表本地立即去除未读形态。
   - 前端：`notification/index.ts`。

5. **卡片点击状态可更明确**
   - 现状：hover opacity 0.96。
   - 补法：未读卡片 hover 可同时把左边条透明度降至 0.85，已读卡片只降白卡透明度。

### 6.3 状态覆盖

- 默认：通知列表。
- 触摸：通知卡、全部已读。
- 加载：通知列表骨架。
- 空：无按钮空状态。
- 错误：重试。
- 操作中：全部已读 loading/disabled。

### 6.4 前端衔接点

- `pages/notification/index.wxml|wxss|ts`
- `components/state-block/index.*`
- `components/page-list-footer/index.*`

---

## 7. 我的页补强

### 7.1 当前已完成基础

- 页面顶部已改为暖色渐变。
- `profile-header` 已包含加载、错误、头像、编辑、认证徽章、身份行、统计与 bio。
- 资料完整度卡片已放在个人头部和常用入口之间。
- 常用入口与功能列表已使用暖绿/砖赭浅渐变占位，功能列表有细描边。
- 通知入口未读使用 `unread-badge`。

### 7.2 仍缺什么

1. **入口图标仍是空占位块**
   - 现状：`assetEntries`、`functionEntries` 的 `icon` 多为空，视觉为渐变块。
   - 补法：在 `entry-grid` 和 `entry-list-item` 内为不同 `key` 绘制 CSS 图标：
     - 我的发布：文档/铅笔
     - 我的点赞：心形或拇指线性图
     - 我的收藏：书签
     - 浏览历史：时钟
     - 实名认证：证件卡
     - 通知中心：铃铛/信封
   - 收益：资产入口可识别性明显提升。

2. **资料完整度缺少加载/失败细节**
   - 现状：可见时展示卡片；失败时多半隐藏。
   - 补法：资料加载时可展示短骨架；资料失败时隐藏完整度卡片但保留 `profile-header` 错误，不阻断整页。
   - 收益：状态更稳定，避免卡片闪现。

3. **资料完整度进度文案可更鼓励**
   - 现状：展示 `n/m` 与 hint。
   - 补法：hint 使用「完善后校友更容易认出你」「补全院系与入学年份，连接更准确」等低压力文案。
   - 边界：不制造强制认证焦虑。

4. **认证徽章圆角可与其他胶囊统一**
   - 现状：`ph__badge` 圆角 `8rpx`。
   - 补法：改为 `999rpx` 或 `12rpx`，与未读/资料完整度行动更统一。
   - 风险：视觉小改，适合 P1。

5. **功能列表点击态与 grid 点击态可更统一**
   - 现状：grid hover 0.94，row hover 0.96，完整度 0.96。
   - 补法：同类卡片统一 0.94 或 0.95；不可点击状态如后续存在，使用 `--color-text-disabled`。

### 7.3 状态覆盖

- 默认：资料头部 + 完整度 + 常用入口 + 功能列表。
- 触摸：编辑、完整度、grid、列表项。
- 加载：`profile-header` 骨架；建议补完整度骨架。
- 错误：资料头部错误 + 重试；入口区仍可保留。
- 空/缺资料：头像字母占位、未认证徽章、资料完整度引导。
- 未读：通知中心 badge。

### 7.4 前端衔接点

- `pages/mine/index.wxml|wxss|ts`
- `components/profile-header/index.*`
- `components/entry-grid/index.*`
- `components/entry-list-item/index.*`
- `components/unread-badge/index.*`

---

## 8. TabBar 补强

### 8.1 当前已完成基础

- 结构保持 4 tab + 中间发布入口。
- 图标已全部使用 CSS 几何自绘，未使用 emoji 或混用图标库。
- 激活态使用 `--color-brand`，未选中为 `--color-text-tertiary`。
- 发布按钮为纯 `--color-cta`，阴影为墨绿色系。
- 消息角标使用 `--color-unread`，支持数字展示。

### 8.2 仍缺什么

1. **Tab item 缺少触摸态**
   - 现状：模板未声明 `hover-class`。
   - 补法：为 `.tabbar__item` 增加 `hover-class="tabbar__item--hover"`，发布按钮增加 `hover-class="tabbar__publish--hover"`。
   - 样式：普通 item opacity `0.85`；发布按钮 opacity `0.9`，不 scale。

2. **发布入口可增加轻微可访问触摸范围说明**
   - 现状：发布圆钮 112rpx，文字在下方。
   - 补法：确保 `.tabbar__publish-wrap` 整块可触发或至少按钮和文案区域都能触发发布。
   - 收益：降低误触/点不到的概率。

3. **激活态缺少非颜色辅助**
   - 现状：主要靠图标/文字变绿和字重。
   - 补法：激活 item 下方可加短线或浅底点，尺寸 `28rpx × 4rpx`，颜色 `--color-brand-muted` 或 `--color-brand` 低透明。
   - 注意：不要影响中间发布按钮高度。

4. **角标边界态可补齐**
   - 现状：已有 `badgeText`，但设计需约定 1-99 与 99+。
   - 补法：`1-9` 最小宽 `28rpx`，`10-99` 自动撑宽，`99+` 保持一行；角标不遮挡消息图标主形。

5. **安全区机型需要视觉验收**
   - 现状：`tabbar--safe` 用 `env(safe-area-inset-bottom)`。
   - 补法：验收 iPhone 刘海/全面屏与普通安卓，确认发布 FAB 不被安全区抬得过高或贴边。

### 8.3 状态覆盖

- 默认：未选中四入口 + 中间发布。
- 激活：图标/文字深墨绿，建议补非颜色辅助。
- 触摸：item 与发布按钮 opacity。
- 未读：红色角标，数字白字，`99+`。
- 安全区：底部 padding。

### 8.4 前端衔接点

- `custom-tab-bar/index.wxml|wxss|ts`
- `miniprogram/app.json` 继续保持 custom tabBar 配置，不改 pagePath。

---

## 9. 推荐落地批次

### P0：低风险一致性补强

- 首页、关注改用或对齐 `page-list-footer`。
- 给 `content-type-tabs`、TabBar、全部已读按钮补齐 `hover-class`。
- `state-block` 增加更明确的空/错图形变体，但仍保持单组件。
- `content-card` 统计行补轻量 CSS 图标。

### P1：页面完成度提升

- 首页分类激活图标浅底、搜索 hover 图标同步变色。
- 发布页字段级错误提示与分节标题图标。
- 我的页入口和功能列表按 key 绘制 CSS 图标。
- 消息类型增加小圆点或基础线性图标。

### P2：需要更多验证或轻业务协同

- 关注页作者行增加「已关注」关系胶囊。
- 发布页键盘聚焦时固定栏行为精调。
- 我的页完整度加载骨架与更细的规则文案。
- TabBar 激活态非颜色辅助在真机上验证是否过重。

---

## 10. 验收清单

- [ ] 一级页与 TabBar 不出现电蓝主视觉、蓝紫发布渐变、emoji 图标。
- [ ] 所有主要可点元素具备触摸反馈，且不使用 scale 造成布局跳动。
- [ ] 首页、关注、消息的信息流尾部加载/失败/结束态一致。
- [ ] 空状态图形与页面语境更贴合，仍保持标题 + 说明 + 单一行动。
- [ ] 发布页错误既有底部摘要，也能在字段附近定位。
- [ ] 我的页入口图标可识别，不再只是无语义渐变占位块。
- [ ] TabBar 激活、未读、安全区、发布入口触摸范围均在真机验证。
- [ ] 所有补强不新增接口字段、不改变一级主流程。
