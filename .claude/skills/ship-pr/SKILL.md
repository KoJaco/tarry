---
name: ship-pr
description: Claude Code only: Run all checks, write the PR description, and flag risks before opening a pull request. Use when the user says ship it, open a PR, ready to merge, or wrap this up.
disable-model-invocation: true
---
# ship-pr

1. `git status` clean? On a feature branch (never main).
2. Run: lint, typecheck, unit tests, `go test -race`, build; e2e if UI/flow changed.
3. Run `review-diff` (reviewer subagent) and `ui-review` if UI changed. Fix blockers.
4. If migrations are present, confirm `migration-guard` evidence (up/down/up on a Neon branch).
5. Write the PR body: **What/Why**, **How to test**, **Screenshots** (UI), **Migration & rollback**, **Risks**, **Cost impact**, **Follow-ups**.
6. Ask for approval, then push and `gh pr create`. Do not merge — merging and production deploys are human-approved in CI.
