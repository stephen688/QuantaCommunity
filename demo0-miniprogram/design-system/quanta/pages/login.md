# Login Page Overrides

> **PROJECT:** Quanta
> **Generated:** 2026-05-21 20:55:11
> **Page Type:** Authentication

> ⚠️ **IMPORTANT:** Rules in this file **override** the Master file (`design-system/quanta/MASTER.md`).
> Only deviations from the Master are documented here. For all other rules, refer to the Master.

---

## Page-Specific Rules

### Layout Overrides

- **Structure:** Single-screen login — no avatar circle, no nickname input on page; authorization only via bottom sheet
- **Background layers (z-index bottom → top):**
  1. `base-gradient` — cream `#FFF8ED` → mid `#FFE8CC`
  2. `bottom-glow` — fixed orange band `#FE6603` at 18% from bottom
  3. `blob-orbs` — 2–3 low-saturation orange/yellow blobs, 15–20s drift
  4. `deco-layer` — semi-transparent paw prints / hexagons / stars (CSS, not emoji)
  5. `mascots-layer` — `mascot-illust type="white"`（`/assets/mascots/cat-white.svg` 路径描摹）；橙/书猫待重绘后恢复
  6. `content` — logo, copy, CTA buttons
- **Logo:** `<quanta-mark />` centered; title "Quanta" + subtitle "连接你我 · 探索无限"
- **CTA:** One primary "微信一键登录" pill; secondary "暂不登录，先逛逛" ghost button

### Spacing Overrides

- Logo section top margin accounts for status bar + nav
- Action section bottom padding leaves room for legal text + safe area

### Typography Overrides

- Title: warm brown `#3D2A1F`, bold
- Subtitle / legal: muted `#8A6B55`
- Legal links: brand primary `#FE6603`

### Color Overrides

| Element | Value |
|---------|-------|
| Page background | `#FFF8ED` → `#FFE8CC` gradient |
| Bottom glow | `#FE6603` at 18% |
| Primary CTA | `#FE6603` → `#FF8C42` gradient pill |
| Secondary CTA | `rgba(255,255,255,0.72)` + warm border |
| Sheet mask | `rgba(61,42,31,0.35)` |
| Sheet panel | white glass `rgba(255,255,255,0.72)` + blur, top radius 40rpx |

### Component Overrides

#### login-sheet (on login page)

- **`autoShow="{{false}}"`** — sheet must NOT auto-open; only triggered by primary CTA tap
- Sheet header: `quanta-mark size="sm"`
- Primary action: "允许并登录" with `#FE6603` gradient
- Skip link: "暂不登录，先看看"

#### quanta-mark

- Inner: `/images/quanta-mark-ref.png` (official QT mark)
- Outer: `quanta-mark__frame` — `border-radius` 52rpx (md), gradient `#1c1c20 → #0d0d10`, warm shadow, top `__shine` highlight; `overflow: hidden` clips square PNG corners

### Motion Overrides

- Entry: staggered fade-up for logo → actions → legal (200–400ms)
- Background: blob drift, mascot float 3–4s; all disabled when `motionReduced` or `prefers-reduced-motion`

---

## Page-Specific Components

| Component | Role |
|-----------|------|
| `quanta-mark` | Brand logo mark |
| `mascot-illust` | CSS-drawn mascots (white cat, orange cat, book cat) |
| `login-sheet` | WeChat profile authorization (manual trigger on this page) |

---

## Interaction Flow

1. App `reLaunch` to login when not authenticated → show login page only (no sheet)
2. User taps "微信一键登录" → `showLoginSheet({ force: true })` → sheet slides up
3. User taps "允许并登录" → `wx.getUserProfile` → `loginWithWeChat`
4. Success → toast → `reLaunch` home tab

---

## Anti-Patterns (Login Page)

- ❌ Auto-opening login sheet on page load (double UI with page buttons)
- ❌ Avatar picker / nickname input on main login screen
- ❌ Large green accent areas
- ❌ Emoji as decorative icons
