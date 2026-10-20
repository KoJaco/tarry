---
name: add-component
description: Build a React UI component or screen (shadcn/ui + Tailwind) that follows the project design tokens, accessibility rules, and state conventions. Use whenever the user asks for a component, page, form, modal, table, or UI change.
---
# add-component

1. Apply the `my-design` skill (which builds on `frontend-design`): read `docs/design/tokens.md`; never hardcode colours/spacing/radii.
2. Prefer existing shadcn/ui primitives (use the shadcn skill/CLI) and local components before creating new ones.
3. Cover **all states**: empty, loading (skeleton), error (what happened + how to fix), success, disabled, long-content overflow.
4. Accessibility: semantic elements, labels, keyboard operation, visible focus, ≥4.5:1 text contrast, `prefers-reduced-motion`, no colour-only meaning, touch targets ≥44px.
5. Responsive from 390px up; no horizontal scroll of the page.
6. Server vs client components (Next.js) or loader/action split (RR7): keep data fetching on the server side where possible.
7. Tests: Testing Library for behaviour; a Playwright flow if it's a key path.
8. Finish with the `ui-review` skill (screenshots at 3 breakpoints).
