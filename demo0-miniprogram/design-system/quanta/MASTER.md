# Design System Master File

> **LOGIC:** When building a specific page, first check `design-system/pages/[page-name].md`.
> If that file exists, its rules **override** this Master file.
> If not, strictly follow the rules below.

---

**Project:** Quanta
**Generated:** 2026-05-21 20:55:11
**Category:** Social Media App

---

## Global Rules

### Color Palette

> **Brand override:** Quanta warm palette replaces skill default rose/blue recommendations.

| Role | Hex | CSS Variable | Usage |
|------|-----|--------------|-------|
| Background Base | `#FFF8ED` | `--color-bg-base` | Page cream background |
| Background Mid | `#FFE8CC` | `--color-bg-mid` | Mid-tone warm gradient stop |
| Background Bottom | `linear-gradient(180deg, transparent → #FE6603 18%)` | `--color-bg-bottom` | Bottom orange glow band |
| Brand Primary | `#FE6603` | `--color-primary` | CTA, emphasis, links |
| Brand Secondary | `#FF8C42` | `--color-secondary` | Hover, secondary accents |
| Accent Star | `#FFD93D` | `--color-accent-star` | Decorative stars, highlights |
| Logo Box | `#141418` → `#0D0D10` | `--color-logo-box` | Dark logo card gradient |
| Text Primary | `#3D2A1F` | `--color-text` | Warm brown body/headings |
| Text Muted | `#8A6B55` | `--color-text-muted` | Secondary copy, legal |
| Panel Glass | `rgba(255,255,255,0.72)` + blur | `--color-panel-glass` | Sheet/card glass panels |
| Overlay | `rgba(61,42,31,0.35)` | `--color-overlay` | Modal/sheet mask |

**Color Notes:** Warm Playful Premium — cream base + orange gradient bottom + dark logo card. Avoid large green areas and generic AI blue templates.

### Typography

- **Heading Font:** Fredoka
- **Body Font:** Nunito
- **Mood:** playful, friendly, fun, creative, warm, approachable
- **Google Fonts:** [Fredoka + Nunito](https://fonts.google.com/share?selection.family=Fredoka:wght@400;500;600;700|Nunito:wght@300;400;500;600;700)

**CSS Import:**
```css
@import url('https://fonts.googleapis.com/css2?family=Fredoka:wght@400;500;600;700&family=Nunito:wght@300;400;500;600;700&display=swap');
```

### Spacing Variables

| Token | Value | Usage |
|-------|-------|-------|
| `--space-xs` | `4px` / `0.25rem` | Tight gaps |
| `--space-sm` | `8px` / `0.5rem` | Icon gaps, inline spacing |
| `--space-md` | `16px` / `1rem` | Standard padding |
| `--space-lg` | `24px` / `1.5rem` | Section padding |
| `--space-xl` | `32px` / `2rem` | Large gaps |
| `--space-2xl` | `48px` / `3rem` | Section margins |
| `--space-3xl` | `64px` / `4rem` | Hero padding |

### Shadow Depths

| Level | Value | Usage |
|-------|-------|-------|
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.05)` | Subtle lift |
| `--shadow-md` | `0 4px 6px rgba(0,0,0,0.1)` | Cards, buttons |
| `--shadow-lg` | `0 10px 15px rgba(0,0,0,0.1)` | Modals, dropdowns |
| `--shadow-xl` | `0 20px 25px rgba(0,0,0,0.15)` | Hero images, featured cards |

---

## Component Specs

### Buttons

```css
/* Primary Button */
.btn-primary {
  background: linear-gradient(135deg, #FE6603, #FF8C42);
  color: #FFFFFF;
  padding: 12px 24px;
  border-radius: 999px;
  font-weight: 600;
  box-shadow: 0 8px 24px rgba(254, 102, 3, 0.28);
  transition: all 240ms cubic-bezier(0.16, 1, 0.3, 1);
  cursor: pointer;
}

.btn-primary:hover {
  opacity: 0.92;
  box-shadow: 0 10px 28px rgba(254, 102, 3, 0.34);
}

/* Secondary Button */
.btn-secondary {
  background: rgba(255, 255, 255, 0.72);
  color: #3D2A1F;
  border: 2px solid rgba(61, 42, 31, 0.12);
  padding: 12px 24px;
  border-radius: 999px;
  font-weight: 600;
  transition: all 240ms cubic-bezier(0.16, 1, 0.3, 1);
  cursor: pointer;
}
```

### Cards

```css
.card {
  background: rgba(255, 255, 255, 0.72);
  border-radius: 12px;
  padding: 24px;
  box-shadow: var(--shadow-md);
  transition: all 200ms ease;
  cursor: pointer;
}

.card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}
```

### Inputs

```css
.input {
  padding: 12px 16px;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  font-size: 16px;
  transition: border-color 200ms ease;
}

.input:focus {
  border-color: #FE6603;
  outline: none;
  box-shadow: 0 0 0 3px rgba(254, 102, 3, 0.12);
}
```

### Modals

```css
.modal-overlay {
  background: rgba(61, 42, 31, 0.35);
  backdrop-filter: blur(4px);
}

.modal {
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(12px);
  border-radius: 40rpx;
  padding: 32px;
  box-shadow: var(--shadow-xl);
  max-width: 500px;
  width: 90%;
}
```

---

## Style Guidelines

**Style:** Warm Playful Premium

**Keywords:** Warm, playful, premium, cream background, orange gradient, dark logo card, mascot decoration, glass panels, community onboarding

**Best For:** Quanta alumni community mini-program, login/onboarding, social feed

**Key Effects:** Multi-layer CSS animation (blob / stars / paw prints / mascot float), 200–400ms transitions with `cubic-bezier(0.16, 1, 0.3, 1)`, respect `prefers-reduced-motion` + app `motionReduced` flag

### Page Pattern

**Pattern Name:** Community/Forum Landing

- **Conversion Strategy:** Show active community (member count, posts today). Highlight benefits. Preview content. Easy onboarding.
- **CTA Placement:** Join button prominent + After member showcase
- **Section Order:** 1. Hero (community value prop), 2. Popular topics/categories, 3. Active members showcase, 4. Join CTA

---

## Anti-Patterns (Do NOT Use)

- ❌ Heavy skeuomorphism
- ❌ Accessibility ignored

### Additional Forbidden Patterns

- ❌ **Emojis as icons** — Use SVG icons (Heroicons, Lucide, Simple Icons)
- ❌ **Missing cursor:pointer** — All clickable elements must have cursor:pointer
- ❌ **Layout-shifting hovers** — Avoid scale transforms that shift layout
- ❌ **Low contrast text** — Maintain 4.5:1 minimum contrast ratio
- ❌ **Instant state changes** — Always use transitions (150-300ms)
- ❌ **Invisible focus states** — Focus states must be visible for a11y

---

## Pre-Delivery Checklist

Before delivering any UI code, verify:

- [ ] No emojis used as icons (use SVG instead)
- [ ] All icons from consistent icon set (Heroicons/Lucide)
- [ ] `cursor-pointer` on all clickable elements
- [ ] Hover states with smooth transitions (150-300ms)
- [ ] Light mode: text contrast 4.5:1 minimum
- [ ] Focus states visible for keyboard navigation
- [ ] `prefers-reduced-motion` respected
- [ ] Responsive: 375px, 768px, 1024px, 1440px
- [ ] No content hidden behind fixed navbars
- [ ] No horizontal scroll on mobile
