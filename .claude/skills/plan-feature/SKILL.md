---
name: plan-feature
description: Claude Code only: Turn a feature idea or bug into a spec, task breakdown, and acceptance criteria before any code is written. Use whenever the user says plan, design, spec, scope, "how should we build", or asks for a change that touches more than ~3 files, a migration, auth, or infra — even if they don't say "plan".
---
# plan-feature

Work in plan mode. Do not write production code.

1. **Interview (max 5 questions, only what you can't infer):** user outcome, constraints, out of scope, who/what is affected, deadline/risk.
2. **Explore** with the `explorer` subagent: relevant files, existing patterns, similar features. Read AGENTS.md rules that apply.
3. **Spec** (`docs/specs/<slug>.md`): problem, goals/non-goals, user flows, data model changes, API changes, UI states (empty/loading/error), security/privacy notes, cost impact, rollout + rollback.
4. **Tasks:** ordered, each independently verifiable, tagged by size (S/M/L) and by executor: `cursor` (well-scoped, mechanical) or `claude` (tricky, cross-cutting, auth/infra/migration).
5. **Acceptance criteria:** testable statements; list the exact test commands.
6. **Risks & open questions:** call out anything needing the user's decision.
7. Finish by offering to run `handoff-to-cursor` for the `cursor` tasks. Stop for approval before implementing.

Keep specs short; link to code rather than pasting it.
