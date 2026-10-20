---
name: review-diff
description: Claude Code only: Review a diff/branch/PR against the project checklist (correctness, tests, security, portability, cost, scope). Use before opening or merging any PR, when the user says review/check/audit my changes, and after any auth, migration, or infra change.
---
# review-diff

Gather: `git diff main...HEAD`, the plan/handoff, and test results. Run the `reviewer` subagent for an independent pass.

Checklist (rank findings blocker / should-fix / nit, cite file:line):
- **Correctness:** edge cases, error handling, concurrency, idempotency, time zones, pagination.
- **Tests:** new behaviour covered; failing-first regression for bugs; no flaky waits.
- **Security:** authz on every route, input validation, injection, secrets in code/logs, CORS, rate limiting, SSRF, dependency risk (see `security-pass` for deep dives).
- **Portability (AGENTS.md rules 2–3):** DATABASE_URL only, plain SQL migrations, JWKS verifier interface, no provider SDK in domain code.
- **Cost hygiene (rule 9):** NAT/ALB/Fargate/VPC Lambda, log retention, unbounded queries, N+1.
- **Migrations:** expand/contract, locks, rollback, backfill batching.
- **UI:** my-design tokens, a11y, states, responsive (`ui-review` evidence attached).
- **Scope:** unrelated changes, drive-by refactors, dead code.

Verdict: approve / approve with fixes / do not merge. Keep it under one page.
