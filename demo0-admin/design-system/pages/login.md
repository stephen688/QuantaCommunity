# Login Page Overrides

> Overrides `design-system/MASTER.md` for the sign-in experience.

## Layout

- Split hero (desktop) + glass form card; single column below 900px
- Hero: community value props with SVG icons (no emoji)
- Form: single `code` field; dev default `test`

## Colors

- Background mesh: deep violet (`#4C1D95` family) with soft lavender glows
- CTA button: primary purple gradient (`--admin-primary` → `--admin-primary-active`)
- Form card: `rgba(255,255,255,0.92)` minimum for light-mode contrast

## Interaction

- Submit: `cursor-pointer`, 200ms transitions, `prefers-reduced-motion` disables mesh animation
- Focus ring on inputs: `--admin-primary-ring`
