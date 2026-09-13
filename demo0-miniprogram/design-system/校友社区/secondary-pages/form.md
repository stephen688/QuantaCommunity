# 发布/编辑组设计稿

> **分组：发布回答页 · 资料编辑页**  
> 关联：`MASTER.md §3.4`、`phase2-overview.md §3.1 表单组补充检索`、`needs-confirm.md §0.2`。  
> 技术栈：微信小程序，样式 `rpx` + CSS 变量，与 `app.wxss` 对齐。

---

## 一、分组共享规则

| 规则 | 说明 |
|------|------|
| 背景色 | `var(--color-bg)` `#F7F5F0`；表单卡片 `var(--color-card-bg)` `#FFFFFF` + `1rpx solid var(--color-border)` |
| 输入框高度 | 单行 `72rpx`；多行最小 `200rpx`；`border-radius: var(--radius-input)` `12rpx` |
| 输入聚焦态 | `border: 1rpx solid var(--color-brand)`；`background: var(--color-card-bg)` |
| 字符计数器 | 右下角；`font-size: var(--font-caption)` `24rpx`；默认 `var(--color-text-tertiary)`；接近上限（≥ 80%）改为 `var(--color-warning)` `#B45309` |
| 字段标签 `.section-title` | `font-size: 26rpx`；`font-weight: 600`；`var(--color-text-primary)`；`margin-bottom: 8rpx` |
| 错误文案 | 紧跟字段下方；`font-size: var(--font-caption)` `24rpx`；`var(--color-danger)` `#C43D3D` |
| 提交按钮 | `background: var(--color-cta)` `#1E5A4C`；`color: #FFF`；高 `84rpx`；`border-radius: var(--radius-button)` `999rpx`；**禁止**蓝紫渐变 |
| 禁用态 | 按钮背景 `#E8EBEF`；字色 `var(--color-text-disabled)` |
| 加载/提交中态 | 按钮 `loading="true"`；`disabled`；文案「提交中…」 |
| 安全区 | `padding-bottom: calc(32rpx + env(safe-area-inset-bottom))` |
| 键盘上移 | 所有 `<textarea>/<input>` 使用 `cursor-spacing="32"` |
| 触摸反馈 | `hover-class` 降透明度 `0.85`；禁止 `scale` |

---

## 二、发布回答页（`pages/publish-answer/index`）

### 2.1 页面目标

基于现有专业问题发布一条回答，操作简洁，提交即离开。

### 2.2 信息区块

```
┌────────────────────────────────────┐
│  问题摘要卡（只读，绿底）           │
│    「正在回答」标签                 │
│    问题标题                        │
│    院系/届元信息                   │
├────────────────────────────────────┤
│  正文编辑卡                        │
│    <textarea> 多行输入              │
│    底部：字数 / 发布按钮            │
│  [加载中]  问题摘要骨架             │
│  [错误时]  state-block              │
└────────────────────────────────────┘
```

### 2.3 问题摘要卡 `.pa__summary`

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-brand-light)` `#E8F2EF`（与 `answer-detail` 问题卡一致） |
| border | `1rpx solid rgba(30,90,76,0.15)` |
| 圆角 | `var(--radius-card)` `16rpx` |
| padding | `20rpx 24rpx` |
| margin | `16rpx 24rpx 0` |
| 「正在回答」标签 `.pa__summary-label` | `font-size: var(--font-caption)` `24rpx`；颜色 `var(--color-brand)`；`margin-bottom: 8rpx`；胶囊可选：底 `var(--color-brand-muted)`，`padding: 4rpx 12rpx`，圆角 `999rpx` |
| 问题标题 `.pa__summary-title` | `font-size: var(--font-title)` `34rpx`；`font-weight: 600`；`var(--color-text-primary)`；`line-height: 1.35` |
| 标题 muted 态 | 问题加载失败时颜色降为 `var(--color-text-tertiary)` |
| 元信息 `.pa__summary-meta` | `font-size: var(--font-caption)` `24rpx`；`var(--color-text-tertiary)`；`margin-top: 6rpx` |
| 错误重试文案 `.pa__err` | `var(--color-danger)` + 「点击重试」链接；`var(--color-brand)` 下划线 |

### 2.4 问题摘要加载态

```
加载时：显示「加载问题信息…」文案（当前实现）
建议升级（P2）：显示骨架——两行宽度不一的灰色块，背景 #E8F2EF
```

### 2.5 正文编辑卡 `.pa__editor`

| 属性 | 规格 |
|------|------|
| 背景 | `var(--color-card-bg)` `#FFFFFF`；`border: 1rpx solid var(--color-border)` |
| 圆角 | `16rpx` |
| padding | `20rpx 24rpx` |
| margin | `16rpx 24rpx 0` |
| `<textarea>` `.pa__input` | `min-height: 260rpx`；`font-size: var(--font-body)` `28rpx`；`line-height: 1.55`；无默认边框；`placeholder` 颜色 `var(--color-text-tertiary)` |
| 底部行 `.pa__editor-foot` | `display: flex; justify-content: space-between; align-items: center; margin-top: 12rpx; border-top: 1rpx solid var(--color-divider); padding-top: 12rpx` |
| 字数 `.pa__count` | `font-size: var(--font-caption)` `24rpx`；默认 `var(--color-text-tertiary)` |
| 字数警告态 `.pa__count--warning` | `var(--color-warning)` `#B45309`（当前实现 `body.length >= 1840`，保持阈值，改颜色 token） |
| 发布按钮 `.pa__submit` | 高 `64rpx`；`padding: 0 28rpx`；`background: var(--color-cta)`；字 `#FFF`；`font-size: 28rpx`；`border-radius: 999rpx`；禁用态背景 `#E8EBEF` + `var(--color-text-disabled)` |

### 2.6 状态覆盖

| 状态 | 处理 |
|------|------|
| 默认（可输入） | 编辑卡输入区正常聚焦 |
| 加载问题中 | 摘要卡显示 loading 文案 / 骨架 |
| 问题加载失败 | 摘要卡显示 `pa__err` 重试链 |
| 字数不足（≤0 字符） | 发布按钮 disabled |
| 字数接近上限（≥80%） | 计数器 warning 色 |
| 提交中 | 按钮 `loading` + `disabled`；输入 `disabled` |
| 提交成功 | `wx.navigateBack()`（已有逻辑，保持） |
| 提交失败 | toast 提示错误（字段旁无需额外红框，因只有一个输入） |
| 参数无效 | `<state-block type="invalidData">` + 返回 |

### 2.7 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 摘要卡背景 | `pages/publish-answer/index.wxss` `.pa__summary` | 改为 `var(--color-brand-light)` |
| 摘要卡 border | `.pa__summary` | 加 `border: 1rpx solid rgba(30,90,76,0.15)` |
| 发布按钮 token 化 | `.pa__submit` | 确认 `var(--color-cta)`，去 `type="primary"` 默认蓝色 |
| 计数器颜色 | `.pa__count--warning` | 改为 `var(--color-warning)` |
| 编辑卡 border | `.pa__editor` | 加 `border: 1rpx solid var(--color-border)` |

---

## 三、资料编辑页（`pages/profile-edit/index`）

### 3.1 页面目标

两种模式：
1. **资料模式**（默认）：修改头像和昵称。
2. **认证模式**（`verifyMode`）：提交实名认证或查看认证状态。

### 3.2 资料模式信息区块

```
┌────────────────────────────────────┐
│  资料卡                            │
│    「头像」标签                    │
│    <media-uploader>（头像上传）    │
│    「昵称」标签                    │
│    <input> 昵称                    │
│    字数计数                        │
│    [保存] 按钮                     │
└────────────────────────────────────┘
```

### 3.3 认证模式信息区块

```
┌────────────────────────────────────┐
│  认证状态卡（已有状态文本）         │
│    驳回原因（审核不通过时）         │
│    认证通过信息（只读）             │
├────────────────────────────────────┤
│  认证表单卡（待提交/驳回重填时）    │
│    身份类型选择（在校/校友）        │
│    真实姓名                        │
│    学号/校友编号                   │
│    Quanta 届（选填）               │
│    所属部门（picker）               │
│    [提交认证] 按钮                 │
└────────────────────────────────────┘
```

### 3.4 表单卡通用样式

| 属性 | 规格 |
|------|------|
| 卡片 `.pe__card` | `background: var(--color-card-bg)`；`border: 1rpx solid var(--color-border)`；`border-radius: 16rpx`；`padding: 20rpx 24rpx`；`margin: 16rpx 24rpx 0` |
| 字段标签 `.section-title` | `font-size: 26rpx`；`font-weight: 600`；`var(--color-text-primary)`；`margin-top: 16rpx`（首个无 top margin）；`margin-bottom: 8rpx` |
| 输入框 `.pe__input` | 高 `72rpx`；`border-radius: 12rpx`；`border: 1rpx solid var(--color-border)`；背景 `var(--color-bg)`；`padding: 0 16rpx`；`font-size: var(--font-body)` `28rpx`；聚焦态 border 改为 `var(--color-brand)` |
| 计数 `.count-text` | `font-size: var(--font-caption)` `24rpx`；`var(--color-text-tertiary)`；右对齐 |
| 计数接近上限 | `≥ 16/20` 时改为 `var(--color-warning)` |

### 3.5 身份类型选择（认证模式）

| 状态 | 规格 |
|------|------|
| 默认 | 胶囊按钮；背景 `var(--color-bg)`；`border: 1rpx solid var(--color-border)`；字 `var(--color-text-primary)` |
| 激活 `.type-chip--active` | 背景 `var(--color-brand-muted)` `rgba(30,90,76,0.12)`；`border-color: var(--color-brand)`；字 `var(--color-brand)` |
| 间距 | 两个 chip 间距 `12rpx` |

### 3.6 部门 Picker

| 属性 | 规格 |
|------|------|
| 行 `.pe__picker-row` | `height: 72rpx`；`display: flex`；`justify-content: space-between`；`align-items: center`；`border: 1rpx solid var(--color-border)`；`border-radius: 12rpx`；`padding: 0 16rpx` |
| 文字 `.pe__picker-text` | `font-size: var(--font-body)`；`var(--color-text-primary)` |
| 箭头 `.pe__picker-arrow` | `font-size: 28rpx`；`var(--color-text-tertiary)` |

### 3.7 认证状态展示

| 认证态 | 处理 |
|--------|------|
| 未提交 / 已驳回 | 显示认证表单 |
| 审核中（`auditStatus === 0`） | 显示提示卡：「资料已提交，审核中」；背景 `var(--color-brand-light)`；文案 `var(--color-text-secondary)` |
| 已通过（`auditStatus === 1`） | 展示只读行列（身份/姓名/学号/届/部门）；`var(--color-text-primary)` / `var(--color-text-secondary)` |
| 已驳回（`auditStatus === 2`） | 驳回卡：背景 `rgba(196,61,61,0.06)`；border `rgba(196,61,61,0.18)`；驳回原因字色 `var(--color-danger)` |
| 认证状态标题 `.pe__status-title` | `font-size: var(--font-title)` `34rpx`；`font-weight: 600` |

### 3.8 提交按钮

| 状态 | 规格 |
|------|------|
| 默认 | `background: var(--color-cta)`；`color: #FFF`；高 `84rpx`；`border-radius: 999rpx`；`margin-top: 24rpx`；`width: 100%` |
| 禁用（上传中） | 背景 `#E8EBEF`；字 `var(--color-text-disabled)` |
| 提交中 | `loading="true"`；`disabled`；文案「保存中…」/「提交中…」 |
| 当前实现问题 | `type="primary"` 使用微信原生蓝，需通过 WXSS 覆盖为 `var(--color-cta)` |

### 3.9 头像上传区域（资料模式）

| 属性 | 规格 |
|------|------|
| 上传组件 `<media-uploader>` | `max-count="1"`（头像唯一）；预览圆形 `border-radius: 50%`；占位底色 `#F0EDE7` |
| 上传中态 | 覆盖 loading 遮罩；背景 `rgba(0,0,0,0.3)` |
| 上传失败 | 显示「上传失败，点击重试」文案；`var(--color-danger)` |

### 3.10 只读行列（认证通过态）

| 属性 | 规格 |
|------|------|
| 行 `.pe__row` | `display: flex; justify-content: space-between; padding: 12rpx 0; border-bottom: 1rpx solid var(--color-divider)` |
| 键 `.pe__k` | `var(--color-text-tertiary)`；`font-size: 26rpx` |
| 值 `.pe__v` | `var(--color-text-primary)`；`font-size: 26rpx` |

### 3.11 状态覆盖

| 状态 | 处理 |
|------|------|
| 初始加载 | `<feed-skeleton variant="home" count="4">` |
| 加载失败 | `<state-block>` + 重试 |
| 输入校验失败 | 字段下方 `var(--color-danger)` 文案（如「昵称不能为空」「请填写真实姓名」） |
| 提交成功 | `wx.navigateBack()` |
| 提交失败 | toast 提示 + 按钮恢复 |

### 3.12 前端衔接点

| 衔接项 | 路径 | 改动 |
|--------|------|------|
| 表单卡 border | `pages/profile-edit/index.wxss` `.pe__card` | 加 `border: 1rpx solid var(--color-border)` |
| 提交按钮去蓝 | `.pe__btn` | WXSS 覆盖 `background: var(--color-cta)`；`border: none`；`color: #FFF` |
| type-chip 激活 | `.type-chip--active` | `var(--color-brand-muted)` 底 + `var(--color-brand)` 字/边框 |
| 计数器颜色 | `.count-text` | token 化为 `var(--color-text-tertiary)`；接近上限改 warning |
| 驳回状态背景 | `.pe__reject`（若无需新增） | 增加 danger 底色规范 |
| 审核中提示卡 | `.pe__hint-only` | 改背景 `var(--color-brand-light)` |

---

## 四、全组改造收益摘要

| 改动 | 优先级 | 收益 |
|------|--------|------|
| 表单卡统一 border | P0 | 层级清晰 |
| 提交按钮去蓝 | P0 | 核心 CTA 品牌一致 |
| 字段标签/计数 token 化 | P0 | 统一排版质感 |
| 问题摘要卡绿底 | P1 | 问题与输入区视觉区分 |
| 身份 chip 激活态 | P1 | 选择态明确可见 |
| 输入聚焦边框 | P1 | 当前焦点清晰 |
| 驳回状态底色 | P1 | 错误语义清晰，来自补充检索 |
| 摘要加载骨架 | P2 | 等待体验统一 |
