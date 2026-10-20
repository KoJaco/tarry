---
name: bootstrap-project
description: Claude Code only: Stand up a new app end to end on the default stack — repo from template, Neon project and branches, Neon Auth, Go Lambda API, Cloudflare frontend, R2 bucket, CI with GitHub OIDC, budgets and alerts. Use when the user says new project, bootstrap, scaffold, set up the stack, or create a new app.
disable-model-invocation: true
---
# bootstrap-project

Run `stack-decision` first (framework, DB/auth, storage). Then follow `references/checklist.md` step by step. Prefer CLIs/APIs (`gh`, `neonctl`, `wrangler`, `aws`) over browsers. **Human-only steps** (account signup, billing, 2FA, captchas, OAuth app creation in provider consoles) are listed explicitly — stop and ask the user to do them, then continue.

Guardrails during bootstrap:
- Create **staging first**; production resources are created only after the user explicitly approves, and prod credentials are never placed in the agent's environment.
- Separate AWS accounts for staging and production (under Organizations); separate Neon projects (prod vs staging/dev); separate Cloudflare Workers/R2 buckets per env.
- All CI deploys use GitHub OIDC → IAM role (no long-lived AWS keys). Cloudflare/Neon tokens are scoped per environment and stored as GitHub environment secrets / in the secrets manager.
- Add Budgets + alerts and log retention before any traffic.

Assets: `assets/go-api/` (tested, Lambda-ready Go skeleton with the JWKS `auth.Verifier`, pinned `go.mod`, `.golangci.yml`) and `assets/web/` (root `package.json` + `pnpm-workspace.yaml` with the scripts AGENTS.md assumes, `wrangler.jsonc` with staging/production envs). Copy and adapt; pin all versions. CI/CD workflows already live in `.github/workflows/` (action SHAs pinned, deploy jobs inert until the repo variables are set).
