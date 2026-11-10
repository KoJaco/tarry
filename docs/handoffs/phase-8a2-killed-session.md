# Handoff: phase 8a.2 — killed-session fix

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-10 · **Branch:** given in your prompt (never main).

Read first: `AGENTS.md` (rules 3, 5, 6, 7), `docs/handoffs/phase-8-launch.md` § "Owner decisions, 2026-11-10".

## Problem
When the process is killed (crash, OOM, reinstall) during a scheduled window, recovery (`CaptureCoordinator.recoverLocked()`, `app/.../tracking/CaptureCoordinator.kt` ~324-367) closes the orphaned session at its **last saved ping** (`minOf(lastPing, now)`), marks it `KILLED`, and writes a `SERVICE_KILLED` Gap from there to now. In STATIONARY mode repeated fixes are dropped by `PingFilter` (DROP_STALE) while the heartbeat still updates (`onFixes`), so the last saved ping can be minutes older than the real death. The Timeline (`ui/timeline/TrackingMarkers.kt`: `trackingMarkers()`, `offStretches()`) then shows Stop/Start or a collapsed "Off" marker and hatches every stop→start pair ("A collapsed pair is its own short stretch"), even across a visit that continues on both sides.

## Owner decision (build exactly this)
1. **End at last heartbeat.** In recovery: `closedAt = max(lastPing ?: open.start, min(heartbeat, now))`, where `heartbeat` is the persisted last heartbeat (see `TrackingStatusStore` / `HealthSnapshot.lastHeartbeatMs`); use it only if `heartbeat >= open.start` (a heartbeat from a previous session must not extend this one). Find how the heartbeat is stored and read; don't add a new store if one exists.
2. **Short outage → nothing.** New `TrackingConfig.shortInterruptionMs = 5 * 60_000L` (KDoc). Recovery writes **no** `SERVICE_KILLED` Gap when `now - closedAt < shortInterruptionMs`. Keep window clipping, orphaned-grace handling and the `health.adoptOpenGap` path as they are (an adopted health gap is already ≥ 20 min).
3. **Markers.** In `trackingMarkers()` a `KILLED` stop followed by a session start within `shortInterruptionMs` produces **no marker** and **no off stretch**. Pass the threshold in from `TrackingConfig` (via `TimelineViewModel`, which builds markers ~277-281) rather than a second literal; keep `TrackingMarkerConfig.MergeWindowMs` for user stop/start collapsing. A `KILLED` stop that does show (longer outage) is labelled **"Interrupted"** (new string, sentence case) instead of "Off"/"Stop"; keep the time range line. TalkBack wording "Tracking interrupted" already exists — keep consistent.
4. **No hatch across a continuing visit.** `offStretches()` (or the canvas that draws them, `DayCanvas.kt` ~291-327) must not hatch the part of a stretch that lies inside a visit which starts before the stretch and ends after it. Clip it out (a stretch partly outside keeps its outside parts). The visit's inner-gap dots / "not tracked" text (`DayLayout.kt` ~178-190, 342-352) stay. Prefer a pure function taking the stretches + visit ranges so it's JVM-testable.
5. Sessions screen stays as is ("ended unexpectedly").

## Tests (rule 7)
- `CaptureCoordinatorTest`: closes at heartbeat not the stale ping; heartbeat before `open.start` ignored (falls back to last ping); no Gap when restart < 5 min; Gap when ≥ 5 min; window clipping still applies. Update `recoveryClosesAtLastPingWithKilledAndWritesGap` to the new semantics (rename).
- `TrackingMarkersTest`: short KILLED stop+start → no markers, no stretch; long → "Interrupted" marker + stretch; user stop/start collapse unchanged; stretch inside a continuing visit → removed; stretch overlapping a visit edge → only the outside part kept.
- One `OffStretchCanvasTest` (or `TrackingMarkersCanvasTest`) case: no hatch drawn over a continuing visit.
- Existing `HealthCheckWorkerTest` / `TrackingControllerTest` recovery tests stay green (adjust only if they encode the old end time, and say so).

## Rules
No segmentation change expected (if you find you need one, stop and report). No Room schema change. Small diffs. `gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by item, explicit paths (include this handoff in the first), never `.idea/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`. Report files, tests, decisions, skips.
