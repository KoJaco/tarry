---
name: my-design
description: My personal UI/UX layer for product interfaces. Use for ANY UI work — components, screens, flows, landing pages, empty/error states, copy — alongside the frontend-design skill. Enforces my design tokens, interaction rules, accessibility floor, and writing style so every app feels consistent and not templated.
---
# my-design

Layering: **(1) `frontend-design`** sets aesthetic direction and anti-"AI default" discipline → **(2) this skill** supplies my tokens and product-UI rules → **(3) `ui-review`** verifies.

## Process
1. Read `docs/design/tokens.md` (copy `references/tokens-template.md` into the repo for a new app and fill it in). Tokens are the source of truth; never hardcode values.
2. Identify the app's subject, audience, and primary job (one sentence each). Write a short design plan: palette (named), type roles, layout concept, principles. Review it against the brief — if any part is a generic default, change it.
3. Build with shadcn/ui primitives mapped onto the tokens (CSS variables). Spend boldness in one place; keep the rest quiet.

## Product-UI rules (customise freely)
- One primary action per view; destructive actions need confirmation + undo where possible.
- All states are designed: empty (invite action), loading (skeleton, no layout shift), error (what happened + how to fix, no apologies), success (confirm what changed).
- Forms: labels above inputs, inline validation after blur, preserve input on error, clear required/optional.
- Density: compact for data-heavy tools, relaxed for consumer flows — decide per app and record it in tokens.md.
- Motion: respond to user actions; avoid decorative entrance animations; honour `prefers-reduced-motion`.
- Copy: sentence case, active voice, buttons say exactly what happens ("Save changes"), same verb across the flow, user vocabulary not system vocabulary.

## Quality floor (non-negotiable)
WCAG 2.2 AA, visible focus, keyboard operable, ≥44px touch targets, responsive from 390px, dark-mode-safe tokens if the app supports it.

## Optional layers (install only what you'll use; read each SKILL.md first and pin versions)
- shadcn/ui skill (real components) · Vercel `web-design-guidelines` + `react-best-practices` (audit/perf) · an accessibility skill (e.g. Addy Osmani's web-quality-skills or AccessLint).
- Pick **one** style engine at most (UI/UX Pro Max *or* Taste Skill) — not both.

Evolve this file: every time you correct the agent's UI output twice the same way, add a rule here.
