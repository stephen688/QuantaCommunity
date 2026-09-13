# 校友社区 · 第三阶段 Design Token Delta（配色微调）

> **层级规则：** 本文件仅描述相对 `MASTER.md` §2.1 的**增量与替换**；未列出的 token **保持不变**。  
> 依据：`phase3-overview.md` §3 Pro-Max 检索；产品要求「缓解满眼绿、不大改品牌」。  
> 落地文件：`miniprogram/app.wxss` 及下文「全局落点」所列组件/页面 WXSS。

---

## 1. 改了什么 / 没改什么 / 为何不算大改

| 类别 | 内容 |
|------|------|
| **保留** | `--color-brand` / `--color-cta` 仍为 `#1E5A4C`；Tab 激活、主按钮、关键品牌识别不变 |
| **微调** | 新增链接色；收藏/次要 Chip/统计/内链等**减少与 brand 同屏堆叠**；`--color-success` 与 brand 略分离 |
| **增强** | 已有 `--color-accent-warm` 承担收藏激活、身份点缀、部分搜索热门 |
| **未改** | 暖纸底 `#F7F5F0`、生活/专业分区语义色、danger/unread 红、字体阶梯、圆角/阴影策略 |
| **为何不算大改** | 主色色相不变；不引入社区紫/电蓝/玫红；仅调整**语义分工**与次要面用色，首屏绿块数量下降 |

**验收（配色维度）：**

- [ ] 首屏（首页推荐 / 详情底栏收起态）不再出现「绿底 + 绿字 + 绿图标」三层叠绿
- [ ] 主 CTA / Tab 选中仍一眼可认墨绿
- [ ] 正文链接、展开、重试与主按钮可区分
- [ ] 对比度：正文 `#1A1D21` on `#F7F5F0` / `#FFFFFF` ≥ 4.5:1；`--color-link` on 白底 ≥ 4.5:1
- [ ] 禁止回退电蓝 `#2563EB`、`#2f6bff` 及蓝紫渐变

---

## 2. Token 变更表

### 2.1 新增

| Token | Hex / 值 | 语义 | 依据 |
|-------|----------|------|------|
| `--color-link` | `#5C6B62` | 正文内「展开全文」「重试」「修改」等**非 CTA** 链接 | **Pro-Max**：Knowledge Base 中性灰 + 独立链接色；**项目取舍**：不用 brand 绿冒充链接 |
| `--color-link-pressed` | `#4A574F` | 链接按下态（`opacity` 或色深） | **MASTER 延续**：按压降对比，无 scale |
| `--color-favorite-active` | `#B45309` | 收藏已选图标/文案（= `--color-accent-warm` 别名，便于语义化引用） | **Pro-Max**：warm accent not monochrome green；**MASTER 延续** accent-warm |
| `--color-favorite-active-bg` | `#FDF6EC` | 收藏已选轻底（可选，底栏图标后景） | 同上 |
| `--color-chip-neutral-bg` | `#F0EDE7` | 非类型、非激活的通用 Chip / 假输入底 | **MASTER 延续**：贴近 `--color-bg`，略深一度 |
| `--color-chip-neutral-text` | `#5C6370` | 通用 Chip 文案（= `--color-text-secondary`） | **MASTER 延续** |
| `--color-stat` | `#5C6370` | 列表点赞/评论/浏览**计数**（非可点击强调） | **项目取舍**：统计不用 brand 绿 |
| `--color-stat-active` | `#1E5A4C` | 用户已点赞等**可交互且已激活**的统计 | **MASTER 延续**：仅「用户动作激活」用 brand |

### 2.2 调整（值变更或用法变更）

| Token | 原值 / 用法 | 新值 / 用法 | 依据 |
|-------|-------------|-------------|------|
| `--color-brand-light` | `#E8F2EF`，广泛用于空状态图标底、搜索 chip、entry 图标底 | **保留 hex**，**缩小使用面**：仅 Tab/Chip **激活**、专业标签底、品牌相关空状态 CTA 区 | **项目取舍**：减少大面积浅绿底 |
| `--color-brand-muted` | `rgba(30,90,76,0.12)`，搜索热门、多页 chip | **保留 hex**，**默认改用** `--color-chip-neutral-bg`；仅「当前选中筛选」用 brand-muted | **Pro-Max**：非 monochrome green |
| `--color-success` | `#2D7A4F` | `#2A6B47`（略深一度，与 `#1E5A4C` brand 可区分） | **Pro-Max**：activity green 与 primary 分离；**MASTER 延续** 成功语义 |
| `--color-content-pro-bg` | `#E8F2EF` | `#EDEAE4`（暖灰褐底） | **项目取舍**：专业标签与生活标签一样偏**中性暖**，字色仍 `#1E5A4C` |
| `--color-content-pro-text` | `#1E5A4C` | 不变 | **MASTER 延续** 专业识别 |
| 收藏激活 | 部分页用 `--color-brand` / 绿图标 | 统一 `--color-favorite-active` | **项目取舍** |
| 内链/展开 | `--color-brand` | `--color-link` | **Pro-Max** + **项目取舍** |

### 2.3 明确不变

| Token | 值 | 说明 |
|-------|-----|------|
| `--color-brand` | `#1E5A4C` | 主品牌 |
| `--color-cta` | `#1E5A4C` | 主按钮（纯色，无渐变） |
| `--color-bg` | `#F7F5F0` | 页面暖底 |
| `--color-accent-warm` | `#B45309` | 暖点缀（与 favorite-active 一致） |
| `--color-content-life-bg` / `-text` | `#F0EBE3` / `#6B5B4F` | 生活分区 |
| `--color-danger` / `--color-unread` | `#C43D3D` | 错误、未读角标 |

---

## 3. 语义用色规则（实现时按此映射，避免单页随意改色）

```
主 CTA、Tab 选中、发布 FAB、关注按钮（主态）     → --color-brand / --color-cta
认证通过、已采纳、活动活跃（状态.success）        → --color-success
收藏已选、校友身份强调、热门（非筛选激活）        → --color-accent-warm / --color-favorite-active
正文链接、展开、重试、次要文字链                  → --color-link
列表统计数字（默认）                              → --color-stat
用户已点赞的数字/图标                             → --color-stat-active 或 --color-brand（二选一，组件内统一）
筛选/分类 Chip 未选中                             → --color-chip-neutral-bg + --color-chip-neutral-text
筛选/分类 Chip 选中                               → --color-brand-light + --color-brand
专业内容类型标签                                  → --color-content-pro-bg + --color-content-pro-text
生活内容类型标签                                  → --color-content-life-bg + --color-content-life-text（不变）
```

---

## 4. `app.wxss` 建议补丁（P0）

在 `page { }` 内**追加**（实现阶段复制到 `miniprogram/app.wxss`）：

```css
  /* ---------- Phase3 token delta ---------- */
  --color-link: #5c6b62;
  --color-link-pressed: #4a574f;
  --color-favorite-active: #b45309;
  --color-favorite-active-bg: #fdf6ec;
  --color-chip-neutral-bg: #f0ede7;
  --color-chip-neutral-text: #5c6370;
  --color-stat: #5c6370;
  --color-stat-active: #1e5a4c;
  --color-success: #2a6b47;
  --color-content-pro-bg: #edeae4;
```

---

## 5. 全局落点（token 级，按文件）

| 模块 | 路径 | Phase3 调整要点 | 优先级 |
|------|------|-----------------|--------|
| 全局变量 | `miniprogram/app.wxss` | §4 补丁；`.link` 工具类可指向 `--color-link` | P0 |
| 内容卡 | `components/content-card/index.wxss` | 底部统计 `.meta` 用 `--color-stat`；已点赞用 `--color-stat-active` | P0 |
| 分类 Tab | `components/content-type-tabs/index.wxss` | 未选中：neutral chip；选中：brand-light + brand | P1 |
| TabBar | `custom-tab-bar/index.wxss` | 选中仍 brand；未选保持 tertiary | P1 |
| 详情标签 | `pages/detail-life`、`detail-pro` 胶囊 | 生活/专业标签按 §3 映射；避免额外 brand 描边 | P1 |
| 搜索页 | `pages/search/index.wxss` | 热门关键词：默认 `chip-neutral`；仅「当前高亮」用 brand-muted | P1 |
| AI 详情 | `pages/search-ai-detail/index.wxss` | 摘要区背景改 `--color-chip-neutral-bg` 或 `--color-accent-warm-light`，标题链用 `--color-link` | P1 |
| 空状态 | `components/state-block/index.wxss` | 装饰底：默认 neutral；**带主操作**的空态可保留小面积 brand-light | P1 |
| 入口网格 | `components/entry-grid/index.wxss` | 图标底改 `--color-chip-neutral-bg`，图标色 `--color-text-secondary` | P1 |
| 详情底栏 | `components/detail-action-bar/index.wxss` | 假输入底 `--color-chip-neutral-bg`；收藏激活 `--color-favorite-active` | P0 |
| 评论列表 | `components/comment-list/index.wxss` | 「回复」链 `--color-link`；点赞数 `--color-stat` / active | P0 |
| 用户主页 | `pages/user-profile/index.wxss` | 关注中/统计：neutral + warm；认证仍 `--color-success` | P1 |
| 个人资产列表 | `pages/my-*`、`browse-history` | Tab 与 content-card 统计色对齐 P0/P1 | P1 |

**禁止：** 在上述落点外单页写死 `#1E5A4C` 作统计色或收藏色（应用 token）。

---

## 6. 组件级示例（收藏 / 链接 / Chip）

### 6.1 收藏图标（`detail-action-bar`、content-card）

| 状态 | 图标/字色 | 背景 |
|------|-----------|------|
| 未收藏 | `--color-text-secondary` | 无 |
| 已收藏 | `--color-favorite-active` | 可选 `--color-favorite-active-bg` 圆角底 |

**依据：** **项目取舍** — 收藏≠品牌认同，不必用墨绿。

### 6.2 正文「展开」链接

```css
.link-inline {
  color: var(--color-link);
  font-size: var(--font-caption);
}
```

**依据：** **Pro-Max** 中性链接；**MASTER 延续** 字号阶梯。

### 6.3 搜索热门 Chip（默认 / 选中）

| 状态 | 背景 | 字色 |
|------|------|------|
| 默认 | `--color-chip-neutral-bg` | `--color-chip-neutral-text` |
| 选中 / 刚点击 | `--color-brand-light` | `--color-brand` |

---

## 7. 与 MASTER §3 组件状态的对齐

| 状态 | Phase3 调整 |
|------|-------------|
| 错误可重试链 | 由 `--color-brand` 改为 `--color-link`（重试非主 CTA） |
| 空状态主按钮 | 仍 Primary（`--color-cta`） |
| Secondary 按钮 | 仍描边 brand（关注、取消关注等主操作） |

---

## 8. 决策溯源速查

| Token / 规则 | Pro-Max 依据 | MASTER 延续 | 项目取舍 |
|--------------|--------------|---------------|----------|
| `--color-link` | 中性文档色 + 非蓝链 | 对比度 §6 | 链接≠CTA |
| `--color-favorite-active` | warm accent not all green | accent-warm 已定义 | 收藏不用绿 |
| `--color-chip-neutral-*` | 暖中性社区底 | `--color-bg` 同系 | 减浅绿块面积 |
| `--color-success` 微调 | activity green 独立 | success 语义 | 与 brand 可区分 |
| `--color-content-pro-bg` 暖灰 | 非紫非蓝社区色板 | 专业字色仍 brand | 标签底去绿 |
| brand / cta 不变 | — | §1.4 定稿 | 用户明确不大改品牌 |
| 拒绝 `#7C3AED` / `#2563EB` | color 检索命中 | §1.3 | 校友可信意象 |

---

## 9. 验收对照表（实现完成后勾选）

| 场景 | 期望 | 通过标准 |
|------|------|----------|
| 首页首卡 | 类型标签生活/专业中性；统计灰色；标题黑色 | 无连续三处 brand 绿 |
| 首页分类 Tab | 未选 neutral；选中 brand | 未选无 brand-light 大底 |
| 详情收起底栏 | 假输入 neutral 底；收藏未选灰、已选暖棕 | 底栏无大面积绿 |
| 详情评论 | 「回复」link 色；点赞数 stat 色 | 与主按钮绿区分 |
| 搜索热门 | 默认 neutral chip | 仅选中项绿 |
| AI 摘要卡 | 区背景 neutral 或 warm-light | 非整卡 brand-light |
| 主按钮 / Tab 选中 | brand / cta | 与改版前一致 |

---

## 10. 相关文档

- 总览与检索全文：`phase3-overview.md`
- 基线 token：`MASTER.md` §2.1
- 落地顺序（待写）：`implementation-priority-phase3.md`
