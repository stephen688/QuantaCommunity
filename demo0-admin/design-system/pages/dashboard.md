# Dashboard Page Overrides

> Overrides `design-system/MASTER.md` for the home / KPI view.

## Data-Dense KPI Cards

- Stat cards: icon + large tabular number + label + “查看” link
- **Pending highlight:** when count > 0, use `--admin-warning` on number + warm card tint (`dash-stat--pending`)
- Grid: 1 col mobile → 2 (640px) → 3 (1100px) → 5 (1400px)

## Hero

- Left accent bar (4px primary)
- Welcome uses `nickName` from session
- Admin badge when `isAdmin`

## Shortcuts

- 6 module entry cards with hover lift (max 2px) and arrow color shift
- All interactive tiles: `cursor-pointer`, visible `:focus-visible` ring
