# 第三阶段需产品确认的大改建议

> 本文件收纳第三阶段设计过程中发现的「新增功能、改变主流程、调整信息架构、需要接口或数据模型支持」的建议。  
> **已纳入默认稿（不得写入本文件）：** 配色 token 微调、一级/二级评论长按删除/举报、详情底栏假输入 + 收起/展开评论 sheet — 见 `MASTER-phase3-token-delta.md`、`components/comment-interaction-phase3.md`、`components/comment-composer-phase3.md`。  
> 未标注已确认的条目不得当作默认实现项；实现顺序见 `implementation-priority-phase3.md` §7。

---

## 1. 确认原则

### 1.1 什么进入本文件

| 类型 | 判断标准 |
|------|----------|
| 新功能 | 当前页面/组件没有入口、路由、接口或数据字段支撑 |
| 主流程变化 | 会改变详情阅读、评论发表、搜索、消息等核心路径 |
| 信息架构调整 | 会新增 tab、Banner、工具栏分区、专业详情内嵌评论流等 |
| 数据模型变化 | 需要后端新增评论媒体、置顶、拉黑关系、推荐字段等 |
| 品牌大改 | 主色从墨绿改为其他色系（属视觉战略，非 token 分层） |

### 1.2 默认设计稿可直接做的边界（第三阶段）

- `MASTER-phase3-token-delta.md` 所列 token 与全局落点（主色墨绿不变）。
- `comment-list` 长按 + 条件 ActionSheet + 树形删除刷新（复用 `deleteComment` / `reportComment`）。
- `detail-action-bar` 假输入 + sheet 内 `comment-composer`（无 @/表情/相册工具栏）。
- 一级 5 页 + 二级 13 页按 `primary-pages-phase3.md`、`secondary-pages-phase3.md` 做样式扫尾。
- 二阶段已确认功能（搜索热门、楼中楼展开、最佳回答采纳）继续有效，不在此重复确认。

### 1.3 与二阶段需确认文档的关系

| 文档 | 关系 |
|------|------|
| `needs-confirm-phase2.md` | 二阶段未确认项**仍暂缓**；若产品在本轮一并确认，应回写对应页面稿并升 P1 |
| 本文件 | 仅列**第三阶段新发现**或**因评论/配色抛光而再次暴露**的大改 |

### 1.4 已确认纳入本轮的条目

| 原编号 | 条目 | 纳入方式 |
|--------|------|----------|
| 2.3 | 一级评论仅保留长按、移除「更多」文字链 | `comment-interaction-phase3.md` §4.6.3 定稿改为仅长按；`comment-list` 一级不渲染「更多」；`implementation-priority-phase3.md` P0 |
| 3.2 | 专业详情（`detail-pro`）问题下内嵌评论流 | 与 `detail-life` 同级：`comment-list` + 底栏假输入 + sheet；评论线程 `{contentId}:q`（无 `answerId`）；见 `comment-composer-phase3.md` §7.3、`secondary-pages-phase3.md` §2.2 |

---

## 2. 评论与互动（第三阶段新增）

### 2.1 评论底栏完整小红书工具栏（@ / 表情 / 相册 / 加号）

- **涉及页面：** `pages/detail-life/index`、`pages/answer-detail/index`；组件 `detail-action-bar`、`comment-composer`（或未来 `comment-composer-sheet`）
- **变更范围：** 展开态底部增加 @ 提及、表情面板、相册选图、更多「+」入口；评论发表支持 `imageUrls` 或多媒体。
- **潜在收益：** 与主流社区评论心智一致，表达更丰富。
- **实现风险：** 键盘 + 多层面板 + 安全区叠加复杂；无后端字段时只能做 UI 壳；审核与举报链路需扩展。
- **接口/数据依赖：** 评论配图字段、上传接口、@ 用户搜索、表情资源或第三方 SDK、内容审核。
- **建议结论：** **不纳入默认稿**。Phase3 仅做「假输入 + textarea + 发送」；详见 `components/comment-composer-phase3.md` §1。

### 2.2 评论「复制」、置顶、拉黑用户

- **涉及页面：** 评论 ActionSheet（`detail-life`、`answer-detail`）；可选用户主页、内容菜单
- **变更范围：** 长按菜单增加「复制」；作者/管理员「置顶」；「拉黑」后隐藏其评论或禁止互动。
- **潜在收益：** 治理与运营能力更强。
- **实现风险：** 置顶需权限与排序接口；拉黑涉及关系链、列表过滤与法律/隐私边界；复制为轻量但需剪贴板权限提示。
- **接口/数据依赖：** `pinComment`、拉黑关系表、评论列表过滤策略；复制无接口。
- **建议结论：** **不纳入默认稿**。Phase3 菜单定稿为「举报 / 删除（本人）」；复制可作 P2 体验项单独评估。

### 2.3 一级评论仅保留长按、移除「更多」文字链

**状态：已确认，纳入本轮正式设计稿。**

- **涉及组件：** `components/comment-list/index.wxml`
- **变更范围：** 去掉一级「更多」，仅 `longpress` 进菜单；`showMore` 属性可保留但详情页不再传入 `true`。
- **潜在收益：** 界面更简洁，与二级回复交互一致（均长按）。
- **实现边界：** 一级、二级菜单项与权限规则不变；可选 P2 `wx.vibrateShort` 补偿发现成本。
- **接口/数据依赖：** 无。
- **建议结论：** 纳入本轮，详见 `comment-interaction-phase3.md` §4.6.3 与 `implementation-priority-phase3.md` §2.2。

---

## 3. 品牌与信息架构

### 3.1 全局品牌主色从墨绿调整为其他色系

- **涉及范围：** `app.wxss`、`MASTER.md`、全站 Tab/CTA/标签语义
- **变更范围：** 将 `--color-brand` / `--color-cta` 从 `#1E5A4C` 改为社区紫、电蓝、玫红等 Pro-Max 推荐色板。
- **潜在收益：** 视觉差异化或更贴近某竞品。
- **实现风险：** 等于品牌重塑；与产品方「不大改品牌」及 MASTER §1.3 反模式冲突；全站回归成本高。
- **接口/数据依赖：** 无；需品牌/设计签字。
- **建议结论：** **不纳入默认稿**。Phase3 仅做 `MASTER-phase3-token-delta.md` 分层用色。

### 3.2 专业详情（`detail-pro`）问题下直接内嵌评论流

**状态：已确认，纳入本轮正式设计稿。**

- **涉及页面：** `pages/detail-pro/index`
- **变更范围：** 在问题主卡与回答列表之间（或问题卡下方）增加 `comment-list` + 底栏假输入 + 评论 sheet，与 `detail-life` 对齐；回答层评论仍在 `answer-detail`（`{contentId}:a:{answerId}`）。
- **潜在收益：** 问题层讨论入口清晰，无需跳转即可评论问题。
- **实现边界：** 复用现有一级/楼中楼、长按删除举报、sheet 发表；`listComments` / `createComment` 传 `contentId`，**不传** `answerId`（问题线程 `commentMockThreadKey` → `{contentId}:q`）。
- **实现风险：** FAB「答」与固定底栏、sheet 的 z-index 需与 `answer-detail` 一致；回答卡「评论 N」仍跳转回答详情讨论。
- **接口/数据依赖：** 复用现有评论 API，无需新接口。
- **建议结论：** 纳入本轮，详见 `comment-composer-phase3.md` §7.3、`comment-interaction-phase3.md` §4.7、`implementation-priority-phase3.md` §2.3。

---

## 4. 一级入口与消息（延续二阶段 + Phase3 扫尾暴露）

### 4.1 消息页 / 首页增加新模块（Banner、私信 Tab 等）

- **涉及页面：** `pages/notification/index`、`pages/home/index`；可能 `custom-tab-bar`
- **变更范围：** 首页顶部 Banner、活动位；消息页拆「互动 / 系统 / 私信」Tab；私信会话列表。
- **潜在收益：** 运营曝光、消息分类清晰。
- **实现风险：** 与二阶段 7.1、7.2 暂缓项叠加；未读红点规则、路由、接口需整套规划。
- **接口/数据依赖：** 活动配置、按类型未读数、私信会话与消息 API。
- **建议结论：** **不纳入 Phase3 默认稿**（与 `needs-confirm-phase2.md` §7.1、§7.2 一致）。样式扫尾仅限现有列表与 token。

### 4.2 新增搜索 Tab 或活动 Tab

- **涉及页面：** `miniprogram/app.json`、`custom-tab-bar/index`
- **变更范围：** 4 tab + 发布 改为 5 tab 或替换某一 tab。
- **潜在收益：** 强化搜索/活动入口。
- **实现风险：** 全局导航习惯改变。
- **建议结论：** **不纳入本轮**（同 `needs-confirm-phase2.md` §7.1）。

---

## 5. 二阶段暂缓项（本轮仍不默认实现）

以下已在 `needs-confirm-phase2.md` 记录，Phase3 **不扩大范围**；若产品确认可单项升 P1 并回写 `implementation-priority-phase3.md`。

| 编号 | 条目 | 建议 |
|------|------|------|
| 2.2 | 搜索输入联想 / 自动补全 | 暂缓 |
| 2.3 | AI 摘要赞踩、重新生成 | 暂缓 |
| 2.4 | 搜索结果高级筛选 | 暂缓 |
| 3.2 | 详情相关推荐 | 暂缓 |
| 4.1–4.4 | 回答草稿、回答配图、资料字段扩展、认证多步骤 | 暂缓 |
| 5.1–5.4 | 收藏夹、批量管理、历史分组、资产筛选 tabs | 暂缓 |
| 6.1–6.4 | 用户主页私信、分区 tabs、粉丝列表、动态时间线 | 暂缓 |
| 7.3 | 全局举报/拉黑（内容级，非评论 Sheet） | 暂缓；评论举报已在 Phase3 默认稿 |

---

## 6. 建议确认顺序（若产品要开新能力）

| 优先 | 建议 | 原因 |
|------|------|------|
| 1 | 2.1 评论完整工具栏 + 配图 | 决定评论 sheet 高度、接口与审核范围 |
| 2 | ~~3.2 专业详情内嵌评论流~~ | **已确认纳入** |
| 3 | 2.2 复制 / 置顶 / 拉黑 | 依赖治理策略与后台 |
| 4 | 4.1 消息/首页新模块 | 依赖运营与消息体系 |
| 5 | 3.1 全局换主色 | 战略级，与 Phase3「微调」目标相悖 |

---

## 7. 本轮明确不阻塞的事项

- 本文件所列条目**不影响** `implementation-priority-phase3.md` 的 P0（token-delta、评论长按、底栏 sheet）。
- P1 一级/二级 token 扫尾、P2 震动/草稿可在未确认大改前提下推进；**2.3、3.2 已确认**，按 P0 与 `detail-life` 同级实现。
- 若产品仅确认单项（例如「评论支持复制」），应单独回写对应组件稿与优先级表，避免一次性扩大 Phase3 范围。
