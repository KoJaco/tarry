---
name: ui-review
description: Verify built UI in a real browser and against guidelines — screenshots at three breakpoints, keyboard/focus, contrast, states, console errors, performance hints. Use after any UI change and before merging, or when the user says check the UI, review the design, or test responsiveness.
---
# ui-review

Review only; report findings, don't edit. Claude Code may run this in the read-only `ui-reviewer` subagent.

1. Start the app (`pnpm dev`) or use the staging URL.
2. With Playwright MCP (or Chrome DevTools MCP for perf/console): screenshot key routes at **390 / 768 / 1280px**, light and dark if supported.
3. Check: keyboard-only path through the main flow, visible focus, labels/roles, contrast, reduced motion, empty/loading/error states, long text overflow, no horizontal page scroll.
4. Console + network errors; obvious perf issues (layout shift, huge images, blocking scripts).
5. Audit code against `docs/design/tokens.md`, Vercel `web-design-guidelines` (if installed), and the accessibility skill (if installed).
6. Output a prioritised list (blocker / should-fix / nit), each with route, viewport, screenshot path, and file/selector. Don't fix in the same pass; hand the list to whoever implements.

Commit stable E2E checks as Playwright tests so regressions are caught in CI. Use a dedicated browser profile; never an account with production-admin sessions.
