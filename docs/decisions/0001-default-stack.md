# ADR 0001 — Default stack, agent workflow, and guardrails

Status: **Draft for approval** · Date: 2026-10-13 · Pricing/feature figures were researched on 2026-10-12/13 from vendor pages and third-party roundups; **re-verify before committing money**.

## 1. Confirmed decisions

**Workflow**
- Claude Code = planning/architecture, escalation for tricky tasks, and the **only** tool used on mobile. Cursor = bulk implementation at the desk, via handoff docs.
- Shared rules in `AGENTS.md`; `CLAUDE.md` imports it; `.cursor/rules/project.mdc` points to it.
- Subagent roster (7): explorer (haiku), implementer (sonnet), test-writer (sonnet), reviewer (opus), migration-guard (sonnet), ui-reviewer (sonnet), deployer (haiku, staging only).
- Skills: plan-feature, handoff-to-cursor, handoff-from-cursor, review-diff, add-endpoint, add-component, add-migration, debug-triage, security-pass, ship-pr, my-design, ui-review, stack-decision, bootstrap-project. All editable; evolve them from repeated corrections.
- UI layers: `frontend-design` (direction) → `my-design` (tokens, product-UI rules, a11y floor) → `ui-review` (browser verification). Optional: shadcn skill, Vercel web-design-guidelines + react-best-practices, an accessibility skill; at most one style engine.
- Browser: Playwright (committed E2E) · Chrome DevTools MCP (debug/perf) · Claude in Chrome (logged-in dashboards, dedicated profile only). Prefer CLIs/APIs. Human-only: signup, billing, 2FA, captchas.

**Stack**
- Frontend on **Cloudflare**. Framework (**Next.js or React Router v7**) chosen **per app from its abstract spec**; you use Next.js more often (tie-breaker).
- **Go API on AWS Lambda — always.**
- **Neon** (Postgres + Neon Auth) by default; **switch to Supabase (DB + auth)** when an app needs better social auth (or MFA/SAML/flat billing).
- **Files: R2 by default**; S3 when an app uses more AWS services.

## 2. Proposed defaults (not yet confirmed — please accept or change)
- Models for agents (above) and the `opusplan`-style split for the main session.
- Pattern: browser → Cloudflare → Lambda; Lambda Function URL kept private-ish via `X-Origin-Secret` + app-level JWT verification; Cloudflare rate-limit rules in front.
- IaC: AWS CDK (TypeScript) for Lambda/IAM/Budgets; wrangler for Cloudflare. (Alternatives: SST, Terraform.)
- goose for SQL migrations; pgx for Go DB access; chi router; arm64 Lambda; Function URL by default, API Gateway HTTP API only when WAF/throttling/custom features are needed.
- Environments: separate AWS accounts (staging/prod), separate Neon projects (prod vs staging/dev; PR branches in staging), separate Workers/R2 per env.
- Agents hold staging credentials only; production deploys via GitHub Actions + OIDC + required-reviewer approval.
- Secrets manager: 1Password CLI or Doppler.

## 3. Why this stack (cost + fit)
| Item | Fact (verify) |
|---|---|
| Cloudflare Workers Paid | $5/month, 10M requests included, no data-transfer charge |
| Vercel | Hobby is non-commercial; Pro $20/seat (use only if justified) |
| Neon | Usage-based, no monthly minimum, scale-to-zero, branching; Launch ≈ $0.106/CU-hr, ≈ $0.35/GB-mo; always-on 1 CU ≈ $77/mo (my arithmetic); Neon Auth free to 60K MAU (Free) / 1M (paid) |
| Supabase | Pro $25/org (100K MAU auth included, then ~$0.00325/MAU); compute always-on; staging+prod ≈ $35 |
| AWS Lambda | Always-free 1M requests + 400K GB-s; arm64 cheaper; avoid NAT/ALB/Fargate for MVPs. As an existing AWS customer you likely get **no signup credits**. |
| Cognito (not chosen) | ~10K MAU free (sources disagree), $0.015/MAU Essentials beyond |
| Cloud Run / Fly / Firebase / Cloud SQL | Evaluated and **not selected** (Lambda always; DB handled by Neon/Supabase) |

Expected cost: **~$0–5/mo pre-launch; ~$10–35/mo at launch** depending on Neon always-on vs scale-to-zero.

## 4. Stack comparison summary
1. Cloudflare + Neon + Lambda — **chosen** (cheapest, agent-friendly branching, fewest new vendors).
2. Cloudflare + Supabase + Lambda — **fallback per app** for better social auth/MFA/SAML or flat billing.
3. Vercel + Supabase/Neon — only for Next-specific needs with revenue.
4. All-AWS (+Cognito) — best governance, more setup.
5. All-Google — DB cost higher; not selected.
6. Fly-centric — not needed (Lambda always).

## 5. Known gaps / risks
- **Neon Auth**: Beta/GA docs conflict; social = Google/GitHub/Vercel; MFA coming; orgs partial. Mitigation: provider-neutral JWKS verifier, internal `users.id`, Better Auth self-host escape hatch, Supabase switch playbook (`stack-decision/references/db-auth.md`).
- **User migration** between auth providers (password hashes, linked accounts) must be tested early.
- **Next.js on Workers**: verify OpenNext compatibility, Node APIs, bundle limits per app.
- **Lambda + Postgres**: pooled connection strings; tiny pools; prepared-statement quirks with poolers.
- **Pricing drift**: Neon rates and free-tier terms have changed recently; Neon is part of Databricks.
- **Third-party skills/MCP** can execute code: read, pin, review.
- **Fields to verify in your Claude Code version:** `opusplan` alias, agent frontmatter keys (`disallowedTools`), skill key `disable-model-invocation`, hook payload shape, Remote Control availability on your plan.

## 6. Open items
- [ ] Approve section 2 proposals.
- [ ] Pick IaC (CDK vs SST vs Terraform).
- [ ] Create AWS staging/prod accounts (or confirm existing ones) and record ids in `docs/runbooks/environments.md`.
- [ ] Choose secrets manager.
- [ ] Decide first app to bootstrap and run `stack-decision` on its spec.
