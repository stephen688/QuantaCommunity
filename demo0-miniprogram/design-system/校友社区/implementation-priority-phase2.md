# 第二阶段前端落地优先级与验收标准

> 范围：一级页面补强 + `app.json` 中 13 个二级页面的 UI 美化落地。  
> 原则：先做不会改业务逻辑的共享组件与状态统一，再按搜索、详情、表单、个人内容、用户主页分组推进。  
> 本文只包含可默认推进的前端工作；涉及新增功能、接口、路由或主流程变化的建议见 `needs-confirm-phase2.md`。其中 2.1 搜索热门推荐、3.1 评论楼中楼、3.3 最佳回答采纳已确认纳入本轮。

---

## 1. 落地原则

### 1.1 优先级定义

| 优先级 | 定义 | 可做类型 |
|--------|------|----------|
| P0 | 低风险、高收益、跨页面一致性基础 | token 复用、去旧蓝、卡片描边、空/错/加载统一、触摸态补齐 |
| P1 | 页面组视觉统一和关键体验补强 | 搜索组、详情组、表单组、个人内容组、用户主页的页面级样式；已确认的轻功能入口 |
| P2 | 需要更多真机验证或轻量逻辑配合 | 专用骨架 variant、键盘精调、更多图标变体、临界状态增强 |
| 暂缓 | 需要产品确认或接口支持 | 私信、搜索联想、收藏夹、批量管理等 |

### 1.2 统一验收底线

- 不出现电蓝主视觉、蓝紫渐变、emoji 图标、玻璃拟态堆砌或装饰性光晕。
- 主色、CTA、链接、激活态使用 `--color-brand` / `--color-cta`。
- 页面背景使用 `--color-bg`，卡片使用 `--color-card-bg` + `1rpx solid var(--color-border)`。
- 所有主要可点击元素有 `hover-class` 或等价触摸反馈，反馈只降透明度或变浅底，不使用 `scale`。
- 所有页面至少覆盖默认、触摸、加载、空、错误、禁用或不可操作态中适用的状态。
- 底部固定栏、TabBar、列表页脚不遮挡内容，必须考虑 `env(safe-area-inset-bottom)`。
- 未确认条目不新增接口字段、不新增页面主流程、不改变现有路由职责；已确认 2.1、3.1、3.3 按对应接口能力落地。

---

## 2. P0：共享基础与一级补强

### 2.1 共享组件状态统一

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 空/错状态图形变体 | `components/state-block/index.*` | 增加公告牌、信封、空列表、网络错误等 CSS 图形变体；保持标题 + 说明 + 单一 CTA | 首页、关注、消息、搜索结果、个人内容页空态不再只有抽象圆形；仍不使用 emoji |
| 列表页脚统一 | `components/page-list-footer/index.*`、首页/关注/搜索/资产列表 | 首页、关注改用或完全对齐 `page-list-footer`；二级列表统一加载中、失败、到底状态 | 加载更多、加载失败、没有更多三态文案和颜色一致；失败可点击区域不小于 `64rpx` |
| 内容卡描边与统计图标 | `components/content-card/index.*` | 卡片加 `1rpx solid var(--color-border)`；图片占位 `#F0EDE7`；赞/藏/评补同体系线性 CSS 图标 | 首页、关注、搜索结果、个人内容列表卡片边界清晰；统计行不再只是纯文字 |
| Tabs 触摸态 | `components/content-type-tabs/index.*` | tab item 增加 `hover-class`，触摸 `opacity: 0.85` | 首页/关注/搜索结果切换时有反馈，且没有布局位移 |
| 骨架颜色统一 | `components/feed-skeleton/index.*` | 骨架底色接近暖纸色和浅墨绿，不回到蓝灰模板感 | 首页、关注、消息、二级列表加载态视觉一致 |

### 2.2 一级页面补强 P0

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 首页列表尾部统一 | `pages/home/index.*` | 列表底部加载/失败/结束态接入 `page-list-footer` 或对齐组件规格 | 下拉刷新、加载更多失败、到底三态与关注页一致 |
| 关注页列表尾部统一 | `pages/follow/index.*` | 同首页；关注空态继续使用单一 CTA | 无关注内容和筛选无内容有不同文案，底部状态一致 |
| 全部已读按钮触摸态 | `pages/notification/index.*` | `全部已读` 增加 `hover-class`；处理中禁用 | 有未读时可点击；处理中不可重复点；失败 toast 后状态恢复 |
| TabBar 触摸态 | `custom-tab-bar/index.*` | 普通 tab 与发布按钮增加 hover；发布按钮不使用 scale | 四个 tab 和中间发布入口均有触摸反馈；发布按钮位置不跳动 |

---

## 3. P1：二级页面分组落地

### 3.1 搜索组

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 搜索历史卡片暖化 | `pages/search/index.wxss` | 历史卡加 border；历史 chip 背景从冷灰改 `var(--color-bg)`；清空按钮触摸态 | 有历史、无历史、加载失败三态清楚；清空后即时切空态 |
| 热门推荐模块 | `pages/search/index.*` | 在搜索历史下方新增热门关键词、热门问题，热门校友有数据时展示 | 无历史时能看到推荐；推荐加载失败不阻断搜索；点击关键词进入搜索结果 |
| 搜索结果关键词 bar | `pages/search-result/index.wxss` | 关键词 bar 加白卡背景、细描边、修改入口品牌色 | 长关键词单行省略；点击返回修改不误触 |
| AI 摘要卡去蓝 | `components/ai-summary-card`、`pages/search-result/index.*` | 摘要卡使用 `--color-brand-light` 和浅墨绿描边 | 加载、成功、暂无摘要、错误四态不出现电蓝/紫渐变 |
| AI 详情卡片层级 | `pages/search-ai-detail/index.wxss` | 头部卡绿底，正文卡白底 + border；分区标签胶囊化 | 摘要全文行高 1.55，可读；来源列表和正文层级清楚 |
| 无结果恢复路径 | `pages/search-result/index.wxml|ts` | 无结果使用 `state-block`，说明换词/切分区，CTA 返回搜索 | 不只显示「0 条结果」；用户有明确下一步 |

### 3.2 内容详情组

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 详情主卡描边 | `pages/detail-life/index.wxss`、`pages/detail-pro/index.wxss` | 内容主卡、问题主卡、回答卡统一 border 和圆角 | 生活详情、专业详情不再像内容直接浮在背景上 |
| 正文排版统一 | 详情组三页 wxss | 标题 `34rpx/600/1.35`，正文 `28rpx/1.55`，元信息 `24rpx` | 长正文可读，标题/正文/元信息层级稳定 |
| 标签胶囊化 | `detail-life`、`detail-pro`、`answer-detail` | 生活/专业/院系/届标签按 MASTER 色板胶囊化 | 标签不使用高饱和蓝或玫红，生活/专业语义清楚 |
| 已采纳 / 当前回答语义 | `pages/detail-pro/index.*`、`pages/answer-detail/index.*` | 已采纳用 success 绿胶囊；当前回答用品牌浅底胶囊 | 状态文案 + 颜色双表达，不只靠颜色 |
| 评论楼中楼展开 | `components/comment-list/*`、`pages/detail-life/index.*`、`pages/answer-detail/index.*` | 后端不改；前端先拉一级评论，点击展开时调用回复列表并挂到对应一级评论下 | 展开有 loading；失败可重试；收起不清空已加载回复；二级回复可继续触发回复输入 |
| 最佳回答采纳流程 | `pages/detail-pro/index.*`、`pages/answer-detail/index.*` | 问题作者可采纳回答；采纳前二次确认；采纳中禁用；成功后同步最佳回答态 | 非问题作者不显示采纳按钮；采纳成功后列表和回答详情状态一致；失败可恢复 |
| 固定底栏安全区 | `detail-action-bar` 或详情页底栏样式 | 底部栏顶边、白底、安全区和 foot-spacer 对齐 | 最后一段正文、评论输入、发布回答 FAB 不被遮挡 |

### 3.3 发布 / 编辑组

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 发布回答去蓝 | `pages/publish-answer/index.*` | 发布按钮覆盖微信默认蓝；摘要卡绿底；编辑卡加 border | 按钮默认/禁用/loading 三态清楚，且都是品牌体系 |
| 发布回答计数状态 | `pages/publish-answer/index.*` | 接近上限时计数器 `--color-warning`；提交中输入禁用 | 空内容不能提交；接近上限有 warning；提交失败可恢复 |
| 资料编辑表单卡 | `pages/profile-edit/index.wxss` | 表单卡白底 + border；输入框聚焦品牌边框；picker 行 token 化 | 默认、聚焦、禁用、错误状态均可区分 |
| 认证状态语义 | `pages/profile-edit/index.*` | 审核中浅墨绿提示，驳回浅红底 + danger 文案，通过只读行列 | 审核中/通过/驳回状态不用同一种灰色文案 |
| 字段错误靠近字段 | `pages/profile-edit/index.wxml|ts` | 校验失败时字段下显示错误文案；按钮恢复可点击 | 错误不只靠 toast；用户知道具体字段 |

### 3.4 个人内容组

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 我的发布 tabs 卡片化 | `pages/my-content/index.wxss` | 审核状态 tabs 外卡加 border；chip 默认暖底、激活墨绿浅底 | 全部/审核中/已通过/已驳回切换可见，触摸态明确 |
| 四个资产页复用列表规范 | `my-content`、`my-liked`、`my-collect`、`browse-history` | 统一 `content-card`、`page-list-footer`、safe-bottom 写法 | 四页加载、空、错误、有数据、加载更多体验一致 |
| 浏览历史工具条 token 化 | `pages/browse-history/index.*` | 工具条白卡 + border；清空按钮品牌色；清空中禁用 | 清空前有确认；清空中不可重复触发；失败 toast 后恢复 |
| 空态文案区分 | 四个资产页 wxml/ts | 发布、点赞、收藏、历史分别使用对应说明和 CTA 策略 | 收藏/点赞不强塞新增流程；我的发布可引导去发布 |

### 3.5 用户主页

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| Hero 卡片可信感 | `pages/user-profile/index.wxss` | 暖色渐变背景 + border；头像暖灰占位 + 浅墨绿边框 | 主页头部与我的页气质一致，不回到蓝灰模板 |
| 认证徽章语义 | `pages/user-profile/index.wxss` | 已认证 success 浅底胶囊；未知/未认证 neutral 胶囊 | 认证状态有文案 + 颜色 + 形态表达 |
| 关注按钮状态 | `pages/user-profile/index.*` | 关注 primary、已关注 secondary、请求中 disabled/busy | 关注/取消关注成功后文案和样式同步切换；请求中防重复点击 |
| 发布列表统一 | `pages/user-profile/index.*` | `content-card showAuthor=false`，页脚接 `page-list-footer` | 用户主页不重复展示作者；列表空/错/加载更多一致 |

---

## 4. P2：体验细节与真机验证

| 任务 | 路径 | 说明 | 验收 |
|------|------|------|------|
| 拆分二级骨架 variant | `components/feed-skeleton/index.*` | 增加 `search`、`detail`、`form`、`assets` variant | 骨架结构更贴近真实页面，不造成加载后大跳动 |
| 表单键盘精调 | `pages/publish/index.*`、`pages/publish-answer/index.*`、`pages/profile-edit/index.*` | 聚焦时检查固定底栏、输入框、错误提示是否被键盘遮挡 | iOS/Android 真机输入标题、正文、认证字段时均可完整操作 |
| `state-block` 场景图形扩展 | `components/state-block/index.*` | 为搜索无结果、空收藏、空消息、网络失败补不同 CSS 图形 | 图形有语义但不喧宾夺主；不新增图片资源也可接受 |
| 入口 CSS 图标补齐 | `entry-grid`、`entry-list-item`、`media-uploader` | 我的页入口、功能列表、上传入口按 key 绘制图标 | 不再只有无语义渐变块；图标线宽和尺寸统一 |
| TabBar 激活非颜色辅助 | `custom-tab-bar/index.*` | 激活 tab 下方短线或浅底点 | 真机确认不过重、不影响发布按钮高度 |
| Motion reduced | 全局 wxss / 组件样式 | 骨架和过渡尊重低动效偏好，所有 hover 不 scale | 动效可控，150-300ms 内完成 |

---

## 5. 推荐实施批次

### Batch 1：共享基础闭环（P0）

**目标：** 先让所有页面的底层状态、卡片和触摸反馈统一。

涉及：

- `components/state-block/index.*`
- `components/page-list-footer/index.*`
- `components/content-card/index.*`
- `components/content-type-tabs/index.*`
- `components/feed-skeleton/index.*`
- `pages/home/index.*`
- `pages/follow/index.*`
- `pages/notification/index.*`
- `custom-tab-bar/index.*`

**验收重点：** 一级页面、搜索结果页、个人内容页的空/错/加载/列表尾部状态一致；没有新增接口或流程。

### Batch 2：搜索与个人内容列表（P1）

**目标：** 优先处理高频入口与列表资产页，收益明显且组件复用多。

涉及：

- `pages/search/index.*`
- `pages/search-result/index.*`
- `pages/search-ai-detail/index.*`
- `components/ai-summary-card/*`
- `pages/my-content/index.*`
- `pages/my-liked/index.*`
- `pages/my-collect/index.*`
- `pages/browse-history/index.*`

**验收重点：** 搜索无结果有恢复路径；搜索页热门推荐不喧宾夺主；AI 区域不使用蓝紫；四个资产页列表体验一致。

### Batch 3：详情阅读与互动（P1）

**目标：** 统一详情阅读层级、标签、回答状态和底部栏安全区。

涉及：

- `pages/detail-life/index.*`
- `pages/detail-pro/index.*`
- `pages/answer-detail/index.*`
- `components/detail-action-bar/*`（如存在）
- `components/comment-list/*`
- `components/comment-composer/*`

**验收重点：** 长正文可读；已采纳/当前回答语义清楚；评论楼中楼按需加载且不改后端；最佳回答采纳流程闭环；评论和底栏不遮挡内容。

### Batch 4：表单与用户身份（P1）

**目标：** 把提交、认证、资料、关注这些关键可信动作补齐状态。

涉及：

- `pages/publish-answer/index.*`
- `pages/profile-edit/index.*`
- `pages/user-profile/index.*`
- `components/media-uploader/*`
- `components/profile-header/*`（如复用）

**验收重点：** 表单错误靠近字段；提交按钮去蓝；认证状态语义清楚；关注态有文案 + 颜色双表达。

### Batch 5：真机细节（P2）

**目标：** 处理键盘、安全区、骨架贴合度、入口图标和动效偏好。

涉及范围按前四批验收结果决定。

**验收重点：** 375px 宽度、普通安卓、iPhone 全面屏、小程序开发者工具均无底部遮挡和横向滚动。

---

## 6. 页面组验收清单

### 6.1 一级页与 TabBar

- [ ] 首页分类、搜索框、卡片、列表尾部触摸和状态一致。
- [ ] 关注 tabs、空关注、列表尾部不再与首页割裂。
- [ ] 发布页固定底栏、字段错误、上传态、计数 warning 均清楚。
- [ ] 消息页全部已读、未读胶囊、空消息、失败重试状态闭环。
- [ ] 我的页资料完整度、入口图标、认证状态和未读 badge 语义清楚。
- [ ] TabBar 发布入口、未读角标、安全区和激活态真机可用。

### 6.2 搜索组

- [ ] 搜索历史卡有默认、空、加载、失败、清空后状态。
- [ ] 搜索页新增热门关键词/热门问题推荐；热门校友仅有数据时显示。
- [ ] 搜索结果无结果不是死胡同，提供换词/返回搜索路径。
- [ ] AI 摘要卡和 AI 详情页不使用电蓝/紫渐变。
- [ ] 搜索结果列表、分页失败、到底状态接入统一页脚。

### 6.3 内容详情组

- [ ] 生活详情、专业详情、回答详情的正文阶梯一致。
- [ ] 内容主卡、问题卡、回答卡、评论区边界清晰但不厚重。
- [ ] 生活/专业/已采纳/当前回答等标签使用语义 token。
- [ ] 评论楼中楼使用前端按需加载方案：展开时请求回复列表，行内 loading，失败可重试。
- [ ] 问题作者可采纳回答，采纳中禁用，采纳成功后专业详情和回答详情状态同步。
- [ ] 固定底栏、发布回答 FAB、评论输入不遮挡正文和安全区。
- [ ] 评论空/错/加载状态不显示为空白。

### 6.4 发布 / 编辑组

- [ ] 发布回答问题摘要、编辑卡、提交按钮均符合品牌 token。
- [ ] 发布回答空内容禁用，提交中禁用，失败后可恢复。
- [ ] 资料编辑资料模式与认证模式表单卡一致。
- [ ] 认证审核中、已通过、已驳回状态语义明确。
- [ ] 字段级错误文案靠近字段，不只依赖 toast。

### 6.5 个人内容组

- [ ] 我的发布 tabs 筛选状态可见，审核语义颜色正确。
- [ ] 我的点赞、我的收藏、浏览历史的列表、空态、错误、页脚一致。
- [ ] 浏览历史清空有确认、清空中禁用、失败可恢复。
- [ ] 四个页面底部安全区不遮挡最后一张卡片或 footer。

### 6.6 用户主页

- [ ] Hero 卡片头像、认证、院系/届、统计和按钮层级清楚。
- [ ] 自己主页显示编辑资料；他人主页显示关注/已关注/requesting 状态。
- [ ] 已认证、未认证状态不只靠颜色区分。
- [ ] TA 的发布为空、失败、加载更多都有统一状态。
- [ ] 不出现私信、分区 tabs、粉丝列表入口等未确认功能。

---

## 7. 不纳入本轮实现的项目

以下项目仍必须等待产品或接口确认，不应在本轮直接编码：

- 搜索输入联想、高级筛选。
- AI 摘要赞踩、重新生成、反馈原因。
- 相关推荐。
- 发布回答草稿箱、回答图片/附件。
- 资料字段扩展、认证多步骤流程。
- 收藏夹、批量管理、资产页筛选 tabs。
- 用户主页私信、内容分区 tabs、关注/粉丝列表入口。
- 新增搜索 Tab、活动 Tab、全局举报/拉黑。

---

## 8. 最终总验收

- [ ] `phase2-overview.md` 中列出的 13 个二级页面均有对应落地任务或明确无需改动项。
- [ ] 所有 P0 项完成后，一级页和二级列表页的共享组件状态一致。
- [ ] 所有 P1 项完成后，搜索、详情、表单、个人内容、用户主页五组视觉风格一致。
- [ ] 所有 P2 项完成后，真机键盘、安全区、动效、骨架贴合度无明显问题。
- [ ] 代码实现不引入 `needs-confirm-phase2.md` 中未确认的大改；已确认 2.1、3.1、3.3 按本文验收。
- [ ] 真机或开发者工具至少验证 375px 宽度、长内容、空数据、网络失败、提交中、底部安全区场景。
