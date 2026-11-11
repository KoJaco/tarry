# Handoff: phase 8a.2 — no Gap before first run; short kill leaves no NO_SESSION gap

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-11 · **Branch:** `feat/phase-8a2` (never main).

Read first: `AGENTS.md` (rules 3, 5, 6, 7), `docs/handoffs/phase-8a2-killed-session.md` (the short-interruption rule this extends).

## Found in the emulator check (2026-11-11)
1. **Gap before the app existed.** Fresh install at 08:00 inside the preset window (07:00–17:00): the DB at once holds a Gap
   07:00 → now (`PERMISSION_LOST` while still on onboarding; `SERVICE_KILLED` on a pre-granted install). The health path starts
   a Gap at `shouldRunSinceMs` (window start) via `HealthGapRecorder.reportOutage(issue, notBeforeMs)`; nothing bounds it by
   install or onboarding time. Segmentation adds a `NO_SESSION` gap up to `overlapMs` (30 min) before the first session
   (`SegmentationRunner.runLocked`: first checkpoint = first session start, `from = checkpoint - overlapMs`).
2. **Short kill → NO_SESSION.** Kill at 08:04:14 (last heartbeat), restart 08:05:31: sessions, markers and health Gaps are
   right (no Gap), but segmentation writes a 77 s `NO_SESSION` gap between the KILLED session's end and the next start
   (anything ≥ `minGapMs` 60 s). It shows as an outage until a later run.

## Owner decisions (build exactly this)
### A. Health: no Gap or alert before the app was set up
- New `fun interface CaptureEpoch { suspend fun notBeforeMs(): Long? }` in `app/.../tracking/` with KDoc: the earliest time a
  Gap may start on this install; **null while first-run onboarding is open** (nothing is lost before the user has set the app up).
- Android impl `TrackingCaptureEpoch` (`@Inject`, bound with `@Binds` in the existing tracking Hilt module):
  - `onboardingStartedAtMs != null && onboardingCompletedAtMs == null` → `null` (onboarding open).
  - otherwise `max(firstInstallTime, onboardingCompletedAtMs if onboardingStartedAtMs != null else 0)`.
  - `firstInstallTime` from `PackageManager.getPackageInfo(packageName, 0).firstInstallTime` behind a tiny injectable
    `InstallTimeSource` interface (fake in tests). Upgraders keep their old install time, so a real outage that began before
    the upgrade still starts at its true time; a pre-granted / permission-restored fresh install is bounded by its install time.
- `HealthGapRecorder` gets `private val epoch: CaptureEpoch = CaptureEpoch { 0L }` as the **last** constructor param (default so
  the eight test call sites stay unchanged; Hilt still injects the binding).
  - `reportOutage`: read the epoch first. `null` → return without writing anything (no Gap, no cause, no alert). Otherwise use
    `max(notBeforeMs ?: 0, epoch)` as the effective `notBeforeMs` everywhere `notBeforeMs` is used today (start clamp and the
    `continuous` check). Keep behaviour identical when the epoch is 0.
  - `reportAttention`: `null` epoch → no-op (no alert mid-onboarding).
  - Do not touch `recover`, `closeOutage`, `adoptOpenGap`, `clampedEnd`.
- After onboarding completes the schedule save reconciles; a still-missing permission is then reported normally, with its Gap
  starting no earlier than the completion stamp. No new trigger needed.

### B. Segmentation input (app glue only — **no change to the `:segmentation` module**)
In `SegmentationRunner.runLocked`, build the input via a pure, unit-testable function (e.g. `internal fun segmentationSessions(...)`
and `internal fun boundedWindows(...)` in `SegmentationMapper.kt` or a small new file in `app/.../segmentation/`):
1. **Bridge short kills:** sort sessions by start; a session whose `endReason == KILLED` and whose end is followed by the next
   session's start within `config.shortInterruptionMs` (strictly less, same rule as `CaptureCoordinator`'s `shortOutage`) has
   its range extended to that start. Longer outages stay as they are. Open sessions keep `end ?: now` as today.
2. **No windows before the first session ever:** clip `windows` to start no earlier than the earliest session start in the DB
   (`sessionDao.getInRange(0, Long.MAX_VALUE).firstOrNull()?.start`, already read for the checkpoint; reuse, don't query twice
   if avoidable). A window entirely before it is dropped. Data-derived on purpose: it also holds after a backup restore.

## Tests (rule 7)
- `TrackingCaptureEpochTest`: onboarding open → null; onboarding finished → max(install, completed); silently skipped
  (started null, completed set) → install time; nothing stamped yet → install time.
- `HealthGapRecorderTest`: epoch null → `reportOutage` and `reportAttention` write nothing and post nothing; epoch after the
  window start → Gap starts at the epoch, not the window start or a heartbeat before it; epoch 0 → unchanged (existing tests).
- `HealthCheckWorkerTest`: fresh install inside a window (window 07:00, installed 08:00, onboarding completed 08:02, background
  location missing, worker at 08:30) → Gap starts 08:02. And with onboarding still open at 08:30 → no Gap, no alert.
- Pure-function tests for B: short KILLED → next start < 5 min is bridged; ≥ 5 min not; a non-KILLED end (USER_STOP) not
  bridged; open session uses now; windows clipped at the first session and a fully earlier window dropped.
- `SegmentationRunnerTest`: one end-to-end case per item — a 77 s kill/restart inside a window writes no `NO_SESSION` gap;
  a first session at 08:00 in a 07:00 window writes no `NO_SESSION` gap before 08:00.

## Rules
No Room schema change. No `:segmentation` module change (if you find you need one, stop and report). Small diffs, no
drive-by refactors. `gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by item (A, B),
explicit paths (include this handoff in the first), never `.idea/` or `.claude/`, each ending with exactly
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push. No adb, no device installs, no
`connectedDebugAndroidTest` (an emulator is attached for the planner; don't touch it). Report files, tests, decisions, skips.
