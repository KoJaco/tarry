# Handoff: phase 8a.2 — review fixes

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-10 · **Branch:** `feat/phase-8a2` (checked out; never main).

Follows the reviewer pass on `main...feat/phase-8a2`. Read `CaptureCoordinator.kt` (recovery ~324-367), `HealthChecker.kt`, `HealthEvaluator.kt`, `HealthGapRecorder.kt`, `TrackingStatusStore.kt`, `ui/onboarding/*`, `ui/TarryApp.kt` (~123-135), `TrackingMarkers.kt`, `PlayLocationClient.kt`.

## B1 (must-fix): a short restart must not become a long false Gap
Today a short outage writes no Gap, but `clearSessionState()` wipes the heartbeat. If `HealthChecker.check()` (worker) runs before the deferred restart, it reports `SERVICE_NOT_RUNNING` and `reportOutage` starts the Gap at `shouldRunSinceMs` (e.g. 07:00 → 11:04 over a fully tracked morning).
- After recovery closes a session (short or not), keep a persisted **last-alive mark** = `closedAt` (e.g. set the heartbeat/last-alive value after clearing session state, or a dedicated field in `TrackingStatusStore` if clearer). `reportOutage` must anchor a new Gap at that mark, never earlier.
- Owner rule: a restart within `TrackingConfig.shortInterruptionMs` shows nothing. So while the service isn't running, `HealthEvaluator` must not raise the service-not-running issue until `now - lastAlive >= shortInterruptionMs` (in addition to the existing `serviceStartGraceMs` from `shouldRunSinceMs`). Past that, the Gap starts at the last-alive mark. Permission / location-off / exact-alarm issues are unaffected.
- Tests: `HealthCheckWorkerTest` (or `HealthLogicTest` + worker): kill at 11:02 with heartbeat 11:01, worker at 11:04 → no issue, no Gap; worker at 11:08 with service still down → Gap starting 11:01 (not 07:00); restart at 11:04 → nothing recorded. Existing recovery/health tests stay green.

## S1: half-finished onboarding must resume
A fresh user who grants location on page 2 and then kills the app is treated as an existing install. Add `onboarding_started_at` (nullable Long, same pattern, excluded from backup like the completed flag) set when onboarding is first shown. `OnboardingGate`: started set and completed unset → show onboarding (the location page is skipped as already granted). The silent "existing install" stamp only applies when started is unset. Tests on the gate.

## S2: don't overwrite a stored schedule
Seed the onboarding schedule from the stored template when one exists (`ScheduleRepository` template), else `Schedule.MON_FRI_7_TO_17`. Test.

## S3: the start-up gate must not crash
In `TarryApp.kt` wrap `needsOnboarding()`: on any exception (not cancellation) log, treat as "no onboarding", and still mark the gate resolved. Test with a throwing gate.

## Nits to fix
- N1: in recovery, a heartbeat (or last ping) later than `now` is ignored (fall back), not clamped to `now`. Test.
- N2: when building markers for a day, also load sessions starting up to `shortInterruptionMs` after the day's end so a 23:58 kill with a 00:01 restart shows nothing. Test.
- N3: `clipOutOfVisits` applies to off stretches from `KILLED` stops only; a deliberate user stop/pause keeps its hatch. Test.
- N4: `PlayLocationClient.currentFix`: an exception whose cause chain contains `SecurityException` → `Denied`. Test if the seam allows; otherwise note it.
- N5: drop the duplicate reconcile in `OnboardingViewModel.saveHours` only if `ScheduleSaver` already reconciles via `updateSchedule` (verify); keep behaviour.

## Rules
No Room schema change, no segmentation change. Small diffs. `gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by item, explicit paths (include this handoff in the first), never `.idea/`, never `.claude/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push. No device installs, no `connectedDebugAndroidTest` (an emulator review may be running; don't touch adb). Report files, tests, decisions, skips.
