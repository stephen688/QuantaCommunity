# 第三阶段前端落地优先级与验收标准

> 范围：配色 token 微调 + 评论长按删除/举报 + 详情底栏收起/展开评论 + 一级 5 页与二级 13 页 token 扫尾。  
> 原则：先 P0 共享 token 与评论交互（跨 `detail-life` / `answer-detail`），再 P1 页面组扫尾，最后 P2 体验增强。  
> 设计依据：`phase3-overview.md`、`MASTER-phase3-token-delta.md`、`components/comment-interaction-phase3.md`、`components/comment-composer-phase3.md`。  
> 大改与新增能力见 `needs-confirm-phase3.md`；**已确认纳入：** 2.3 一级仅长按、3.2 `detail-pro` 问题下评论流（见 §1.3）。

---

## 1. 落地原则

### 1.1 优先级定义

| 优先级 | 定义 | 可做类型 |
|--------|------|----------|
| P0 | 跨页面基础 + 用户明确的核心目标 + **已确认 2.3、3.2** | `app.wxss` token-delta；`comment-list` 长按且**无一级「更多」**；`detail-action-bar` + sheet；`detail-life` / `detail-pro` / `answer-detail` 接入 |
| P1 | 一级/二级按新 token 扫尾 | 组件/页面 WXSS 映射；搜索热门 neutral；收藏暖色；链接色 |
| P2 | 真机增强、非阻塞体验 | 长按震动、评论草稿保留、底栏仅图标 |
| 暂缓 | 需产品确认 | `needs-confirm-phase3.md` **未确认**条目；二阶段 `needs-confirm-phase2.md` 未确认项 |

### 1.3 已确认纳入本轮（`needs-confirm-phase3.md`）

| 编号 | 条目 | 优先级 |
|------|------|--------|
| 2.3 | 一级评论移除「更多」，仅 `longpress` | **P0** — `comment-list` + 三详情页不传 `show-more` |
| 3.2 | `detail-pro` 问题下内嵌 `comment-list` + 底栏 sheet | **P0** — 评论 API 仅 `contentId`（问题线程 `{contentId}:q`） |

### 1.2 统一验收底线（延续 MASTER + Phase3 delta）

- 不出现电蓝主视觉、蓝紫渐变、emoji 图标、玻璃拟态堆砌或装饰性光晕。
- **主 CTA、Tab 激活**仍使用 `--color-brand` / `--color-cta`（`#1E5A4C`）。
- **统计默认** `--color-stat`；**已点赞** `--color-stat-active`；**收藏已选** `--color-favorite-active`；**内链/重试** `--color-link`。
- 首屏/详情收起底栏不出现连续三处 brand 绿块（见 `MASTER-phase3-token-delta.md` §9）。
- 按压反馈用 `opacity` / 浅底，**禁止** `scale`；动效 150–300ms。
- 固定底栏、sheet、键盘需 `env(safe-area-inset-bottom)`；列表底部 spacer 与底栏高度对齐。
- 评论删除/举报**不新增**接口；列表刷新以局部更新为主。

---

## 2. P0：Token 基础 + 评论交互

### 2.1 全局 token（`MASTER-phase3-token-delta.md` §4）

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| Phase3 CSS 变量 | `miniprogram/app.wxss` | 在 `page {}` 追加 `--color-link`、`--color-favorite-active*`、`--color-chip-neutral-*`、`--color-stat*`、`--color-success` 新值、`--color-content-pro-bg` | 开发者工具可读到新变量；旧页不报错 |
| 链接工具类（可选） | `miniprogram/app.wxss` | `.link` / `.link-inline` → `var(--color-link)` | 重试/展开链非 brand 绿 |
| 内容卡统计 | `components/content-card/index.wxss` | 统计默认 `--color-stat`；已点赞 `--color-stat-active` | 首页/关注/资产列表底部不过绿 |
| 评论列表链与统计 | `components/comment-list/index.wxss` | 「回复」`--color-link`；点赞数 stat / active | 与主按钮可区分 |
| 详情底栏 token | `components/detail-action-bar/index.wxss` | 假输入 `--color-chip-neutral-bg`；收藏激活 `--color-favorite-active` | 收起态无大面积绿 |

### 2.2 评论长按删除/举报（`comment-interaction-phase3.md`）

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 一级/二级 longpress | `components/comment-list/index.wxml\|ts` | `.cl__item`、`.cl__reply-row` 增加 `bindlongpress`；`enableLongPressMore` 默认 true；`commentmore` 带 `depth`、`userId` | 二级长按可出菜单；点回复/点赞不触发菜单 |
| 移除一级「更多」 | 同上 | 删除一级 `onMoreTap` / 「更多」节点；详情页 `show-more="{{false}}"` 或不传 | 一级仅长按出菜单；二级不变 |
| 生活详情权限与刷新 | `pages/detail-life/index.ts` | `onLoad` 取 `currentUserId`；`itemList` 按作者展示删除；`removeCommentFromTree` 支持二级 | 非本人无删除；删二级不闪全表 |
| 专业详情评论交互 | `pages/detail-pro/index.ts` | 接入 `comment-list`、长按菜单、`currentUserId`、树形删除（同 life） | 问题下评论可举报/删除 |
| 回答详情权限与刷新 | `pages/answer-detail/index.ts` | 同左；删二级优先树形更新，避免一律 `runCommentsReset` | 删回复后 `replyCount`、展开文案正确 |
| 删除确认色 | 两详情页 `showModal` | `confirmColor` 对齐 `--color-danger` `#C43D3D` | 与 MASTER 危险色一致 |
| ActionSheet 文案 | 两详情页 `onCommentMore` | 作者：`['举报','删除']`；非作者/未登录：`['举报']` | 与现状「始终显示删除」不同且符合稿 |

### 2.3 底栏假输入 + 评论 sheet（`comment-composer-phase3.md`）

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 底栏布局改造 | `components/detail-action-bar/index.wxml\|wxss\|ts` | 左 `composer-trigger` 假输入 + 右三图标；事件 `composeropen`；评论 icon 不再仅 scroll | 收起态见「说点什么…」+ 赞/藏/评 |
| Sheet 容器 | `pages/detail-life/index.wxml\|wxss\|ts` | 根级 `comment-sheet` 遮罩+面板；`composerVisible`；移除列表内常驻 `comment-composer` 与 `#composer-anchor` | 默认无大块 textarea；点假输入展开 |
| 生活详情接入 | 同上 | `openComposer` / 关遮罩；`comment-composer` 仅 sheet 内；`autoFocus`；回复顶栏 | 发送流程与现网一致；键盘不挡发送 |
| 回答详情底栏+sheet | `pages/answer-detail/index.*` | **新增** `detail-action-bar`；移除讨论区内嵌 composer；FAB z-index 40 < 底栏 50 < sheet 100 | 与 FAB「答」不互挡；底栏 fixed |
| composer 属性 | `components/comment-composer/index.*` | `autoFocus`；展开 placeholder「友善评论，文明发言」；`cursor-spacing` / `adjust-position` | 真机可输入；500 字计数可见 |
| 页脚 spacer | `detail-life`、`answer-detail` wxss | `foot-spacer` / `fab-spacer` ≥ `calc(112rpx + env(safe-area-inset-bottom))` | 末条评论不被底栏挡 |
| 专业详情评论流 | `pages/detail-pro/index.*` | 问题卡下 `comment-list`；底栏假输入 + sheet（**已确认 3.2**）；`listComments`/`createComment` 仅 `contentId` | 与 life 同款交互；FAB/底栏/sheet z-index 同 answer-detail |
| 专业详情底栏 | `components/detail-action-bar` + `detail-pro` | `onDetailComment` → `openComposer`（替换 scroll/toast） | 假输入可发表评论 |

---

## 3. P1：一级页 + 二级页 token 扫尾

### 3.1 共享组件（token-delta §5）

| 任务 | 路径 | 具体改动 | 验收 |
|------|------|----------|------|
| 分类 Tabs neutral | `components/content-type-tabs/index.wxss` | 未选中 neutral；选中 brand-light + brand | 首页/关注/搜索未选无浅绿底 |
| TabBar | `custom-tab-bar/index.wxss` | 选中 brand；未选 tertiary（不变） | 与 Phase2 一致，无回归 |
| 空状态装饰 | `components/state-block/index.wxss` | 默认装饰底 neutral | 空态不抢 CTA |
| 我的入口网格 | `components/entry-grid/index.wxss` | 图标底 neutral；图标 secondary | 我的页首屏减绿 |
| 页脚失败链 | `components/page-list-footer/index.wxss` | 重试 `--color-link` | 与主按钮区分 |

### 3.2 一级页面（`primary-pages-phase3.md`）

| 任务 | 路径 | 要点 | 验收 |
|------|------|------|------|
| 首页 | `pages/home/index.wxss` + 关联组件 | 分类激活、搜索框 hover、空态 neutral | 首屏无三连绿 |
| 关注 | `pages/follow/index.wxss` | tabs 与首页 token 对齐 | 顶部 tab 一致 |
| 发布 | `pages/publish/index.wxss` | 计数 warning、提交钮仍 cta | 不去蓝回归 |
| 消息 | `pages/notification/index.wxss` | 未读仍 unread 红；类型图标线宽统一 | 不新增私信 Tab |
| 我的 | `pages/mine/index.wxss` + `entry-grid` | 入口图标 neutral；认证 success 与 brand 分屏 | 资料区层次清楚 |
| TabBar 触摸 | `custom-tab-bar/index.*` | hover 延续 Phase2 | 发布钮无 scale |

### 3.3 二级页面分组（`secondary-pages-phase3.md`）

| 分组 | 路径 | Phase3 要点 | 验收 |
|------|------|-------------|------|
| 搜索 | `pages/search/index.*`、`search-result/*`、`search-ai-detail/*` | 热门 chip neutral；AI 区减 brand-light 大块；关键词「修改」link 色 | 搜索页/AI 页不过绿 |
| 详情 | `detail-life`、`detail-pro`、`answer-detail` wxss | 标签 pro 暖灰底；底栏/sheet 见 P0 | P0 交互 + 标签色 |
| 表单 | `publish-answer/*`、`profile-edit/*` | 提交钮 cta；错误链 link；认证 success 与 brand 区分 | 无电蓝按钮 |
| 资产 | `my-content`、`my-liked`、`my-collect`、`browse-history` | tabs neutral/激活；列表 stat 色 | 四页列表一致 |
| 用户 | `pages/user-profile/index.wxss` | 关注钮 brand；已关注 secondary；统计 neutral | 收藏/统计不用绿 |

---

## 4. P2：体验增强（可选批次）

| 任务 | 路径 | 说明 | 验收 |
|------|------|------|------|
| 长按震动 | `comment-list` 或页面 `onCommentMore` | `wx.vibrateShort({ type: 'light' })`，fail 忽略 | 有则更好，无不影响 |
| 评论草稿 | `detail-life`、`answer-detail` ts | 关 sheet 保留 `composerValue`；再开恢复 | 可选；未做则关 sheet 清空 |
| 底栏仅图标 | `detail-action-bar` wxml | 隐藏「点赞」「收藏」汉字 label | 假输入更宽；与附图一致 |
| `hold-keyboard` | `comment-composer` | 发送前保持键盘 | 减少发送后闪退 |
| 真机键盘 | 详情 sheet | `cursor-spacing` 增至 48 若遮挡 | iOS/Android 发送钮可见 |

---

## 5. 推荐实施批次

### Batch 1：Token + 内容卡/评论样式（P0 前半）

**目标：** 全局变量就绪，评论行与底栏视觉符合 delta。

涉及：

- `miniprogram/app.wxss`
- `components/content-card/index.wxss`
- `components/comment-list/index.wxss`
- `components/detail-action-bar/index.wxss`（仅 token 相关样式）

**验收：** 变量生效；评论「回复」为 link 色；未改交互。

### Batch 2：评论长按与删除刷新（P0）

**目标：** 一级/二级长按菜单、权限化删除、树形刷新闭环。

涉及：

- `components/comment-list/index.*`
- `pages/detail-life/index.ts`
- `pages/answer-detail/index.ts`

**验收：** §2.2 表全部通过；一级无「更多」；`detail-pro` 评论长按与删除刷新通过。

### Batch 3：底栏 + sheet + 双详情页（P0）

**目标：** 假输入收起态、sheet 展开态、移除常驻 composer。

涉及：

- `components/detail-action-bar/index.*`
- `components/comment-composer/index.*`
- `pages/detail-life/index.*`
- `pages/detail-pro/index.*`（含问题下评论流 + sheet）
- `pages/answer-detail/index.*`（含新增底栏）

**验收：** §2.3 表全部通过；三详情页 FAB/底栏/sheet 层级正确。

### Batch 4：一级 + 二级扫尾（P1）

**目标：** 按 `primary-pages-phase3.md`、`secondary-pages-phase3.md` 应用 token，不重复 Phase2 P0。

涉及：`content-type-tabs`、`state-block`、`entry-grid`、`page-list-footer`、一级 5 页 wxss、二级 13 页 wxss（详情交互已在 Batch 2–3）。

**验收：** `MASTER-phase3-token-delta.md` §9 场景表勾选。

### Batch 5：P2 与真机（可选）

涉及：震动、草稿、底栏 label、键盘微调。

---

## 6. 页面组验收清单

### 6.1 P0 核心（必须）

- [ ] `app.wxss` 含 Phase3 token；无页面硬编码统计绿。
- [ ] 一级、二级评论长按弹出菜单；非本人无「删除」。
- [ ] 删除一级：列表移除、评论数 -1；删除二级：父级 `replies` 与 `replyCount` 更新。
- [ ] `detail-life`：收起底栏 + sheet 发表评论；无列表内常驻 composer。
- [ ] `answer-detail`：同款底栏 + sheet；FAB 可点；讨论区无内嵌 composer。
- [ ] `detail-pro`：问题下有 `comment-list` + sheet；评论仅绑定 `contentId`；回答讨论仍在 `answer-detail`。
- [ ] 一级评论无「更多」，仅长按出菜单。
- [ ] 收藏已选为暖棕 `#B45309`，非 brand 绿。

### 6.2 一级页（P1）

- [ ] 首页/关注分类与 tabs：未选 neutral，选中 brand。
- [ ] 消息、我的：未读/危险色与 success/brand 不同屏堆叠。
- [ ] TabBar 与 Phase2 触摸态无回归。

### 6.3 搜索组（P1）

- [ ] 热门 chip 默认 neutral；AI 摘要区非整卡浅绿。
- [ ] 搜索结果「修改」为 link 色。

### 6.4 详情组（P0+P1）

- [ ] 生活/回答详情评论交互满足 `comment-interaction-phase3.md` §6。
- [ ] 收起/展开满足 `comment-composer-phase3.md` 验收表。
- [ ] 专业标签底 `#EDEAE4`；生活标签不变。

### 6.5 表单 / 资产 / 用户（P1）

- [ ] 发布回答、资料编辑提交钮仍为 `--color-cta`。
- [ ] 四资产页列表 stat 色与 `content-card` 一致。
- [ ] 用户主页关注/认证/统计色符合 delta。

---

## 7. 不纳入本轮实现的项目

以 `needs-confirm-phase3.md` 为准，**禁止**在 P0/P1 提前编码：

- 评论底栏完整工具栏（@、表情、相册、加号）及评论配图。
- 评论复制、置顶、拉黑用户（菜单外能力）。
- 全局品牌主色更换（非 token 分层）。
- 首页 Banner、消息私信 Tab、新增搜索/活动 Tab。
- `needs-confirm-phase2.md` 中未确认的二阶段大改（联想、AI 赞踩、收藏夹等）。
- `needs-confirm-phase3.md` 中**未确认**项：2.1 完整工具栏、2.2 复制/置顶/拉黑、3.1 换主色、4.x 新模块等。

---

## 8. 设计稿对照索引

| 用户目标（美化UI提示词） | 设计稿 | 本文件批次 |
|--------------------------|--------|------------|
| 配色微调 | `MASTER-phase3-token-delta.md` | Batch 1、4 |
| 二级评论删除/举报 | `components/comment-interaction-phase3.md` | Batch 2 |
| 详情评论收起/展开 | `components/comment-composer-phase3.md` | Batch 3 |
| 全局扫尾 | `primary-pages-phase3.md`、`secondary-pages-phase3.md` | Batch 4 |

---

## 9. 最终总验收

- [ ] 四项 Phase3 目标均可追溯到上表设计稿与具体文件路径。
- [ ] P0 完成后可在 `detail-life`、`detail-pro`、`answer-detail` 完成：长按举报/删自己的评论、底栏假输入发评论；`detail-pro` 为问题线程评论。
- [ ] P1 完成后首屏与搜索/AI 区「满眼绿」缓解，主 CTA 仍可识别。
- [ ] 未实现 `needs-confirm-phase3.md` 任一条目。
- [ ] 真机或开发者工具验证：375px 宽、长评论列表、键盘顶起、安全区、删除二级回复、收藏激活暖色。
- [ ] 与 `implementation-priority-phase2.md` 已验收项无冲突（楼中楼、热门推荐、采纳流程仍有效）。
