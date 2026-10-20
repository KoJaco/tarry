---
name: debug-triage
description: Systematically reproduce, isolate, and fix a bug with a regression test. Use whenever something is broken, flaky, slow, or "not working", an error/stack trace is pasted, or a deploy misbehaves — before guessing at fixes.
---
# debug-triage

1. **Reproduce:** exact steps/inputs/environment. If you can't reproduce, say so and gather logs (CloudWatch, Workers logs, browser console via DevTools MCP).
2. **Scope:** what changed recently (`git log`, deploy history)? Which layer — web, Worker, Lambda, DB, auth, network?
3. **Isolate:** bisect (git bisect or toggling flags), minimise the repro, check the cheapest hypotheses first (config/env, auth token claims, CORS, cold start, pooled-connection settings, timeouts).
4. **Fix root cause**, not the symptom; smallest change.
5. **Regression test** that fails before and passes after.
6. **Verify** on staging with the original repro; note rollback.
7. Write a 3-line postmortem in the PR: cause, fix, how to detect sooner. Propose a guardrail (test, lint rule, alert) when the class of bug can recur.
