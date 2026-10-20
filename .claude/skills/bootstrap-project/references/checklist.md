# Bootstrap checklist (default stack)

Legend: 🤖 agent can do · 👤 human only · ✋ ask first

## 0. Decide
- 🤖 Run `stack-decision`; write the ADR in `docs/decisions/`.

## 1. Repo
- 🤖 `git init`, monorepo layout: `web/`, `api/`, `db/migrations/`, `infra/`, `docs/`.
- 🤖 Repo is created from this template (agentic kit already present); fill in the app-specific bits of AGENTS.md.
- 🤖 Fill in `docs/design/tokens.md` (seeded from the `my-design` template).
- 🤖 `.gitignore` and `.github/dependabot.yml` ship with the template; enable the npm/gomod Dependabot entries once `web/` and `api/` exist.
- 👤 Create GitHub repo + branch protection (require CI + review on `main`); create `staging` and `production` Environments (production: required reviewers).

## 2. Neon (DB + auth)
- 👤 (once) Neon account; create **two projects**: `<app>-prod`, `<app>-staging`. Create scoped API keys per project.
- 🤖 In staging: create `main` branch DB, enable Neon Auth (if default), note the pooled connection string, JWKS URL, issuer, audience.
- 🤖 Set autoscaling limits and keep scale-to-zero on for staging.
- 🤖 Add goose migrations dir; first migration (users mapping table: internal `users.id` ↔ provider subject).
- 👤 Configure OAuth apps (Google/GitHub) in provider consoles; paste client ids/secrets into the secrets manager.

## 3. Go API on Lambda
- 🤖 Copy `assets/go-api/` → `api/` and rename the module (steps in its README); deps are already pinned in `go.mod`. Run `go vet`, `go test -race`, `golangci-lint run` before changing anything.
- 🤖 Copy `assets/web/package.json` + `pnpm-workspace.yaml` to the repo root and `wrangler.jsonc` into `web/`; set names, API URLs and the compatibility date.
- 🤖 IaC (proposal: AWS CDK in TypeScript under `infra/`) for: Lambda (arm64, `provided.al2023`, 256–512 MB), `live` alias, Function URL (auth NONE; app verifies JWT + origin secret) or API Gateway HTTP API if WAF/throttling is needed, IAM role (least privilege), **CloudWatch log group with 14-day retention**, AWS Budgets + alert + optional budget action.
- ✋ Create the staging AWS account / role; production later.
- 🤖 GitHub OIDC provider + deploy roles per account (trust limited to repo + environment).

## 4. Frontend on Cloudflare
- 🤖 Scaffold Next.js (OpenNext) or RR7 per decision; wire `API_BASE_URL`; auth client; tokens → CSS variables; shadcn init.
- 🤖 `wrangler.jsonc` with `staging` and `production` envs; secrets via `wrangler secret put` (staging only).
- Recommended pattern (proposed): route `/api/*` through the Worker/Cloudflare so the Lambda URL stays private, add Cloudflare rate-limit rules, and send an `X-Origin-Secret` header that the Lambda verifies.
- 👤 DNS/domain in Cloudflare; custom domains for web and API.

## 5. Files
- 🤖 R2 bucket per env (or S3 if decided); CORS for presigned uploads; scoped credentials; lifecycle rules.

## 6. CI/CD
- 🤖 Workflows are already in `.github/workflows/` (SHAs pinned; Dependabot keeps them current). Set repo/Environment variables to activate: `STAGING_FUNCTION_NAME`, `PROD_FUNCTION_NAME`, `NEON_STAGING_PROJECT_ID`, `AWS_REGION`, role ARNs, URLs, `CLOUDFLARE_ACCOUNT_ID`; secrets per Environment. Add `packageManager` to `package.json` (pnpm/action-setup needs it).
- 👤 Branch protection on `main`: require PR, review, and the `ci-ok` check; enable the `production` Environment with required reviewers. Update `CODEOWNERS` if the repo moves to an org.
- Flow: PR → CI + preview DB branch → merge → staging deploy → manual approval → production deploy.

## 7. Agent guardrails
- 🤖 Confirm `.claude/settings.json` allow/ask/deny and hooks work (try a blocked command; try reading `.env`).
- 🤖 Document environments + account ids in `docs/runbooks/environments.md` (used by `deployer` pre-flight).
- 🤖 Browser tooling: Playwright + Chrome DevTools MCP are pinned in `.mcp.json` and `.cursor/mcp.json` (keep identical; dedicated browser profile).
- 👤 Configure Cursor command approval + sandbox: see `docs/runbooks/cursor-guardrails.md` (not covered by `.claude/settings.json`).
- 🤖 Prerequisite: `jq` installed (hooks fail closed without it).

## 8. Verify
- 🤖 Smoke: `/healthz`, signup/login, authenticated call, file upload via presigned URL.
- 🤖 `review-diff`, `security-pass`, `ui-review`. Fix blockers.
- 🤖 Cost check: no NAT/ALB/Fargate/VPC Lambda; log retention set; budgets active.
