# Design tokens — <app name>

> Copy to `docs/design/tokens.md`. Map to CSS variables in `globals.css` / Tailwind theme. Keep names semantic.

## Brief
- Subject / audience / primary job:
- Density: compact | comfortable | relaxed
- Aesthetic direction (one line, specific to this app):

## Color (named, with hex; define light + dark)
| Token | Light | Dark | Use |
|---|---|---|---|
| --bg | | | page background |
| --surface | | | cards/panels |
| --text | | | body text |
| --text-muted | | | secondary text |
| --border | | | dividers/inputs |
| --accent | | | primary action (one accent) |
| --accent-fg | | | text on accent |
| --danger / --success / --warning | | | status |
Contrast check: text/bg ≥ 4.5:1, large text/UI ≥ 3:1.

## Type
- Families + roles (display, body, mono if needed):
- Scale (px/rem) and line-heights:
- Max line length: ≤ 75ch

## Space, radius, elevation
- Spacing scale: 4, 8, 12, 16, 24, 32, 48, 64
- Radius: (one value per hierarchy level, justify differences)
- Shadows: (max 2 levels)

## Motion
- Durations/easing; reduced-motion fallback

## Components
- Button hierarchy, input, table, empty state, toast, modal conventions

## Do / Don't
- Do:
- Don't:
