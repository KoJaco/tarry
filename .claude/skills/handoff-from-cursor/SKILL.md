---
name: handoff-from-cursor
description: Claude Code only: Take over a task Cursor couldn't finish. Use when the user says Cursor is stuck, a `-blocked.md` handoff exists, tests keep failing after repeated attempts, or a tricky bug/refactor needs a fresh pair of eyes.
---
# handoff-from-cursor

1. Read `docs/handoffs/<task>.md` and `<task>-blocked.md` (what was tried, errors, hypotheses). Read `git diff` of the branch.
2. Reproduce the failure yourself first. Don't trust the summary.
3. Form 2–3 hypotheses, test the cheapest first (use `explorer` for tracing).
4. Fix the root cause with the smallest change; keep Cursor's good work, revert only what's wrong.
5. Add a regression test (`test-writer` if sizeable).
6. Run `reviewer` on the final diff.
7. Append "Resolution" to the blocked file: root cause, fix, and a one-line lesson. If the lesson is reusable, propose an addition to AGENTS.md or a skill.
