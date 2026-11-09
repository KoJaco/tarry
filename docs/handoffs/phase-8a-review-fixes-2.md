# Handoff: phase 8a.1 — second review fixes (pruned watermark, hide reset, margin, tests)

**From:** Claude (planner, `phase-8`) → **To:** implementer · **Date:** 2026-11-09 · **Branch:** `feat/phase-8-launch` (checked out; never main).

Follows `phase-8a-review-fixes.md`. Read `PingRetention.kt`, `SegmentationUpgrade.kt`, `core-data/.../di/DatabaseModule.kt` (`rewindFloor`), `TimelineEditor.kt` (`rewindCheckpoint`, `resetToDetected`), `SettingsRepository.kt`, `BackupManager.kt`/`BackupStore.kt` (restore, deleteAll), `ui/drawer/EditDrawerState.kt:86` (`canReset`), `EditDrawerViewModel.kt` (~81–89 ctor, ~398 VISIT state), `RetentionSafetyTest.kt`.

## M1 (must-fix): the floor follows what was actually pruned
Today `RewindFloor` and `SegmentationUpgrade` use `now - currentDays`. After shorten → prune → lengthen, the floor moves back past deleted pings and an edit or a `SEGMENTATION_VERSION` upgrade rewinds into pruned days (B1 again).
- Add a monotonic **pruned-before watermark** (epoch ms, nullable) in DataStore. Put it in `SettingsRepository` next to the retention keys but **not** in the user-facing `Settings` model if that model is UI-only; a small dedicated accessor is fine. No Room change.
- `PingRetention.prune()`: after a successful `deleteOlderThan(cutoff)`, set watermark = `max(existing, cutoff)` (only ever increases).
- One shared floor function used by **both** `RewindFloor` (DatabaseModule) and `SegmentationUpgrade`: `floor = max(retentionFloor, watermark?.plus(retentionPruneMarginMs) ?: 0)`, where `retentionFloor = now - days` (0 for Forever). Put it in one place (e.g. a `RetentionFloor` class in core-data, or have `SegmentationUpgrade` use the injected `RewindFloor`); no duplicated formula. The margin constant must be reachable from core-data; move it or pass it in, your call, keep it a single named constant.
- **Restore:** after restore, set the watermark from the restored data: if the earliest restored session starts more than `overlapMs` before the earliest restored ping, watermark = earliest ping timestamp; otherwise clear it. (A backup made after pruning has sessions with no pings.)
- **Delete all:** clear the watermark (no data left to protect).
- Tests: shorten → prune → lengthen to Forever, then (a) an edit of a day −60 visit and (b) an upgrade rewind via `SegmentationUpgrade.runIfNeeded()` + run leave day −60 rows and gaps unchanged; restore of a pruned backup sets the watermark; delete-all clears it; watermark never decreases.

## S-a: hide "Reset to detected" before the floor
- `EditDrawerState`: add `beforeRewindFloor: Boolean = false`; `canReset` gets `&& !beforeRewindFloor`.
- `EditDrawerViewModel`: inject `RewindFloor`; when building the VISIT state set `beforeRewindFloor = v.start < rewindFloor.floorMs()`.
- Keep the editor's `NotFound` guard as a backstop (same floor).
- Test: drawer for a visit before the floor shows no Reset button; after the floor it does.

## S-b: margin on the checkpoint bound too
`PingRetention` cutoff: subtract `retentionPruneMarginMs` from the segmentation bound as well (`checkpoint - overlap - margin`, and the first-session fallback likewise). Adjust tests.

## S-c: more B1 coverage
In `RetentionSafetyTest`, add an undo-restore case (edit an old visit, undo, run → no rewind below floor, neighbours live) and a place-op case (e.g. rename/merge places touching old visits → checkpoint never below floor).

## Nits
- `RestoreHooks.captureSuspendedKeepingOverrides`: make it abstract (update fakes) so a new implementation can't silently drop overrides.
- `PingRetention`: check the `TrackingConfig` default ctor arg under `@Inject` — make sure Hilt supplies the app's `TrackingConfig` (match how other injected classes take it).
- `SettingsDataUiTest`: import `isShorterRetention`.

## Rules
Small diffs, tests per change, `gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by item, explicit paths (include this handoff in the first), never `.idea/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`, no Room schema change (stop and report if needed). Report files, tests, decisions, skips.
