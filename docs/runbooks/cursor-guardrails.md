# Cursor guardrails (TODO: configure per machine/team)

`.claude/settings.json` permissions and `.claude/hooks/block-dangerous.sh` apply to **Claude Code only**. Cursor reads `AGENTS.md`, `.cursor/rules/`, `.claude/skills/` and `.cursor/mcp.json`, but nothing in the repo yet restricts what commands its agent may run.

## To do (status: not configured)
- [ ] **Command approval:** keep Cursor out of "run everything"/yolo mode. Use an explicit allowlist mirroring the `allow` list in `.claude/settings.json` (pnpm test/lint/typecheck/build/e2e, go test/vet/build, read-only git/gh, `wrangler dev`) and require approval for everything else.
- [ ] **Denylist:** mirror `.claude/hooks/blocked-patterns.txt` (force push, push to main, production env flags and DB URLs, destructive SQL, `curl | sh`).
- [ ] **Sandbox:** enable Cursor's sandbox/terminal restrictions; no network or filesystem access beyond the workspace unless needed.
- [ ] **Secrets:** exclude `.env*`, `secrets/`, `~/.aws`, `~/.ssh` from Cursor's context/indexing (`.cursorignore`).
- [ ] **Commit what Cursor supports as project config** (verify the current file names/schema in Cursor's docs: CLI permissions config, hooks, sandbox, `.cursorignore`). Keep it in sync with `.claude/hooks/blocked-patterns.txt`.
- [ ] **MCP:** `.cursor/mcp.json` must stay identical to `.mcp.json` (same servers, same pinned versions).

## Shared backstop (applies to both tools)
- Branch protection on `main`: PR required, review required, `ci-ok` required, no force-push.
- Agent credentials are dev/staging only; production deploys run in GitHub Actions behind the `production` Environment approval.
- `CODEOWNERS` covers `.claude/`, `.cursor/`, `.github/`, `.mcp.json`, `db/migrations/`, `infra/`.
