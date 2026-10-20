@AGENTS.md

# Claude Code specifics

## Roles
- You are the **planner/architect and the escalation path.** Use plan mode for non-trivial work.
- Delegate to the project subagents in `.claude/agents/` rather than doing everything in the main context:
  `explorer` (cheap search) · `implementer` · `test-writer` · `reviewer` (fresh context, read-only) · `migration-guard` · `ui-reviewer` · `deployer` (staging only).
- Use the skills in `.claude/skills/` (plan-feature, handoff-to-cursor, handoff-from-cursor, review-diff, add-endpoint, add-component, add-migration, debug-triage, security-pass, ship-pr, my-design, ui-review, stack-decision, bootstrap-project).

## Models
- Main session: Opus for planning/architecture (try the `opusplan` alias via `/model`, verify it exists in your version).
- Subagent models are set in each agent file (aliases track the latest release).

## Mobile / remote sessions
- Sessions started with `claude --remote-control` can be driven from the Claude mobile app. Keep prompts short and point at files ("implement docs/handoffs/x.md").
- Approval prompts arrive on the phone; do not ask for approvals for routine allowlisted work.
- One session per git worktree. Rename sessions (`/rename`) so the phone list is readable.

## Browser tools
- Playwright (MCP/CLI) for committed E2E tests · Chrome DevTools MCP for debugging/perf · Claude in Chrome only for logged-in dashboard chores, in a dedicated Chrome profile.
- Prefer CLIs/APIs (`wrangler`, `aws`, `neonctl`, `gh`) over driving a browser. Human-only: signup, billing, 2FA, captchas.
