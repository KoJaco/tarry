---
name: handoff-to-cursor
description: Claude Code only: Write a tight implementation brief for Cursor from an approved plan. Use when the user says hand off, give to Cursor, "write the brief", or when a plan has well-scoped tasks ready to implement at the desk.
---
# handoff-to-cursor

Create `docs/handoffs/<task-slug>.md` from `docs/handoffs/_TEMPLATE.md`.

- One handoff per independently shippable task; keep it implementable in a single session.
- Be explicit: files to touch, files to leave alone, exact steps, definition of done, verification commands.
- Copy only the constraints that matter from AGENTS.md; reference the rest.
- Include examples of existing patterns to imitate (file paths).
- State what to do if blocked: write `<task-slug>-blocked.md` and stop.
- Mention the branch/worktree name.

Do not include secrets. Do not include design alternatives — the decision is already made. Output the path and a 3-line summary.
