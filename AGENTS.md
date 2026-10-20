# AGENTS.md — shared project conventions (Claude Code + Cursor)

> Single source of truth. `CLAUDE.md` imports this file; `.cursor/rules/project.mdc` points here.
> Keep it short. Every line should prevent a mistake the agent would otherwise make. Fill the TODOs per project.

## Default stack (details: docs/decisions/0001-default-stack.md)

- **Frontend:** Next.js *or* React Router v7 — chosen per app from its spec (use the `stack-decision` skill). Deployed to **Cloudflare Workers** (Next.js via OpenNext). Vercel only when justified in an ADR.
- **API:** **Go on AWS Lambda** (arm64, `provided.al2023`). Always.
- **DB + auth:** **Neon Postgres + Neon Auth** by default. Switch to **Supabase** (DB + auth) when the app needs broader social login (e.g. Apple), MFA, or SAML, or wants a flat predictable bill.
- **Files:** **Cloudflare R2** by default. Use **S3** when the app already leans on other AWS services.
- **UI:** React + Tailwind + shadcn/ui. Tokens live in `docs/design/tokens.md`. Use the `my-design` skill.



## Commands

Valid once `bootstrap-project` has run (the bare template has no `web/` or `api/`). Run from the repo root; root `package.json` scripts target `web/`. Adjust here if an app deviates.

- install: `pnpm install`
- dev: `pnpm dev`            | api local: `cd api && go run ./cmd/local`
- test: `pnpm test`          | `cd api && go test ./... -race`
- lint: `pnpm lint`          | `cd api && golangci-lint run`
- typecheck: `pnpm typecheck`
- e2e: `pnpm e2e`
- migrate (dev/staging only): `goose -dir db/migrations postgres "$DATABASE_URL" up`



## Architecture map

- `web/` frontend · `api/` Go Lambda API (own `go.mod`) · `db/migrations/` SQL migrations (goose) · `infra/` IaC · `docs/` ADRs, handoffs, runbooks, design tokens
- `.claude/skills/` shared skills (Claude + Cursor) · `.claude/agents|hooks|settings.json` Claude-only · `.cursor/` Cursor-only · `.github/` CI/CD, CODEOWNERS, PR template
- Skills whose description starts with "Claude Code only:" orchestrate subagents; Cursor should not run them.



## Hard rules

1. Never commit to `main`. Work on a branch, preferably in its own git worktree.
2. **Portability:** the app talks to Postgres via `DATABASE_URL` only. SQL migrations only. No provider SDK calls or provider-coupled RLS in domain code until an ADR says otherwise.
3. **Auth:** the Go API verifies JWTs via **JWKS** behind the `auth.Verifier` interface. No provider SDK inside handlers.
4. **Secrets:** never read or write `.env`*, credentials, or tokens. Secrets come from the secrets manager / CI. Never print them.
5. **Environments:** agents touch **dev and staging only**. Production changes ship through CI with a human approval gate.
6. **Tests:** every behaviour change ships with a test. CI must be green before merge.
7. **Plan first** for anything touching more than ~3 files, a migration, auth, or infra (use the `plan-feature` skill).
8. **Small diffs.** No drive-by refactors. If you notice unrelated problems, list them; don't fix them.
9. **Cost hygiene:** no NAT Gateway, ALB, always-on Fargate, or VPC-attached Lambda without an ADR. Set a short retention on every CloudWatch log group. Prefer scale-to-zero.
10. **UI:** follow `my-design`; run `ui-review` before merging UI changes; meet WCAG 2.2 AA.
11. **Guardrails differ per tool:** `.claude/settings.json` and hooks don't govern Cursor. Cursor command approval/sandbox is configured per `docs/runbooks/cursor-guardrails.md`; CI, branch protection, and staging-only credentials are the shared backstop.
12. **Don't guess** — if the plan or handoff is ambiguous, stop and ask rather than invent requirements.



## Handoffs (Claude ⇄ Cursor)

- Claude plans and writes `docs/handoffs/<task>.md` (template: `docs/handoffs/_TEMPLATE.md`).
- Cursor implements from that file. If stuck after two honest attempts, write `docs/handoffs/<task>-blocked.md` (what you tried, error output, hypotheses) and stop.
- If Cursor is not available, implement using Claude following the agents spec provided in .claude/agents/*
- Mobile sessions are Claude Code only.

