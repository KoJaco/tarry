# Runbook — directing agents from your phone (Claude Code only)

Cursor is **not** used on mobile. All mobile work runs through Claude Code on the workstation.

## Setup (once)
1. Workstation: disable sleep (macOS `caffeinate -dims`, Linux `systemd-inhibit`/power settings), stable network, run sessions inside `tmux`.
2. Claude Code signed in with the same account as the Claude mobile app.
3. Check Remote Control availability for your plan/version in Anthropic's docs (it launched as a research preview).

## Per task
1. `git worktree add ../wt-<task> -b <task>` and `cd` into it.
2. Start a session: `claude --remote-control` (or `/remote-control <name>` inside a session; QR code/link opens it in the mobile app → Code tab). `/rename` the session.
3. Give a short instruction that points at files: "Implement docs/handoffs/<task>.md using the implementer agent; run reviewer; stop before push."
4. Approvals arrive on the phone. Routine work is allowlisted in `.claude/settings.json`; `git push`, `gh pr create`, deploys, and migrations always ask.
5. Review diffs, CI results, and `ui-review` screenshots from the phone; merge/prod approvals happen in GitHub (required reviewers).

## Limits to plan around
- Terminal must stay open; one remote connection per session; a network outage of roughly 10 minutes can time the session out. Many sessions can run in parallel (one per worktree).
- Outbound HTTPS only; no inbound ports needed.

## Safety
- Never use `--dangerously-skip-permissions` outside a disposable container/VM with no production credentials.
- The workstation holds **staging** credentials only.
- Use a dedicated Chrome profile for any browser-driven agent work.
