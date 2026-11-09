# Handoff: phase 8a.1 — review fixes (retention safety, delete-all overrides, retention confirm)

**From:** Claude (planner, `phase-8`) → **To:** implementer · **Date:** 2026-11-09 · **Branch:** `feat/phase-8-launch` (checked out; never main).

Context: `docs/handoffs/phase-8a-data.md` built retention and delete-all. Review found the guarantee "pruning never changes what segmentation produces" doesn't hold. Fix all of the following, each with tests. Read `PingRetention.kt`, `RetentionWorker.kt`, `SegmentationRunner.kt` (`runLocked`, widening at ~76–91), `SegmentationUpgrade.kt`, `core-data/.../repository/TimelineEditor.kt` (`rewindCheckpoint` ~425, `rewindForPlaces` ~548, `RewindFloor`), `core-data/.../di/DatabaseModule.kt` (`rewindFloor` provider), `BackupStore.kt` (`deleteRecorded`), `BackupManager.kt`, `TrackingController.kt` (`stopLocked`, overrides).

## B1 (blocker): every checkpoint rewind honours the floor
`TimelineEditor.rewindCheckpoint` has no floor; only place ops clamp to `RewindFloor`. Visit edits (trim, resize, split, merge, reassignPlace, delete, insertManual, resetToDetected, undo restore) rewind to the visit start even if that day's pings were pruned, and the next run soft-deletes the unfrozen visits/trips on pruned days and adds NO_FIX gaps.
- Clamp inside `rewindCheckpoint` itself: `maxOf(editStart, rewindFloor.floorMs())` for all callers (then the place-op clamp at ~398/548 becomes redundant; simplify only those two call sites, nothing else).
- Edits to old visits still save (they're frozen, so segmentation doesn't need to re-run there). Verify `resetToDetected` on a visit older than the floor: unfreezing it would let a later run delete it. Make it either keep the visit frozen-safe (refuse with a clear `EditResult` that the UI already handles, or disable the action) — pick whichever the existing EditResult/UI supports with the smallest change, and report which.
- Test (Robolectric + in-memory Room, real `TimelineEditor` + `SegmentationRunner` + `PingRetention`): pings over ~120 days with detected visits/trips, retention 90, prune, then edit (e.g. reassignPlace) a visit at day −100, run the runner, assert unfrozen neighbours on day −100 are still live and no new NO_FIX gaps appear there.

## S1: prune with a margin behind the floor
The runner widens `from` to the start of any unfrozen visit/trip/segmentation gap overlapping the range plus a trip's origin visit, so it can read before `floor - overlap`.
- Add `TrackingConfig.retentionPruneMarginMs` (2 days). `PingRetention` prunes at `now - days - margin` (then the existing min with open session and segmentation start). `RewindFloor` and `SegmentationUpgrade` stay at `now - days`.
- Update the KDoc on `PingRetention` to state the real guarantee.
- Test: a visit straddling `now - days` survives an upgrade-style rewind to the floor + run after prune with its start and a widened trip's distance/`fromVisitId` unchanged.

## S2: no checkpoint yet
`SegmentationRunner` falls back to the first session start when there's no checkpoint. `PingRetention` must mirror that: when `checkpointDao.getById()` is null, use the earliest session start (`sessionDao.getInRange(0, Long.MAX_VALUE).firstOrNull()?.start`, or a cheaper existing query) as the segmentation bound. Test it.

## S3: delete all keeps the user's active pause/start (owner, 9 Nov)
`deleteRecorded()` deletes every manual override, including an active user pause or manual start, so tracking restarts at once after delete-all. Owner decision: **active manual overrides that existed before the delete survive.** Only for `deleteAll` (restore keeps replacing everything as today). Understand what `stopLocked`/`captureSuspended` writes during the hook and make sure the end state equals the pre-delete override state (a user pause stays paused; no stray temporary pause is left behind when the user had none). Test with the real `TrackingController` (or the closest real collaborator the existing tests can build), not only fake hooks.

## S4: tests
- `RetentionWorker`: enqueued as unique periodic work with KEEP and the `TrackingConfig` period (use `WorkManagerTestInitHelper` if already a test dep, else test the enqueue function's request object); returns retry/failure as the code intends on exception.
- The integration tests above.

## S5: confirm before shortening retention (owner, 9 Nov)
- When the picked value is **shorter** than the current one (Forever counts as longest), show an `AlertDialog`: title "Remove older route lines", body "Route lines older than %s will be removed from the map. Visits, trips and distances stay. This can't be undone." (%s = "30 days" / "90 days" / "1 year"), `DestructiveButton` "Remove older route lines", `TertiaryButton` "Cancel". Cancel keeps the old value. Lengthening applies immediately as now.
- Show the hint on the Settings row too: row subtitle "90 days · older days lose map route lines" (or the existing row's two-line subtitle pattern if there is one; keep it short).
- Compose tests: shortening shows the dialog and only confirm calls the setter; lengthening calls it directly; works at font 2.0.

## Nits (do these too)
- `LicencesScreen.kt`: read the asset off the main thread / `remember`ed with an IOException fallback text ("Couldn't open this licence.").
- Licence list: add OkHttp (Square, Apache-2.0) to the Apache row's subtitle; check `gradle/libs.versions.toml` and the `app` dependencies for any other direct runtime library missing from the list and add it.
- `RetentionWorker`: import `CancellationException` instead of the FQN. `LicencesScreen.kt`: move `@OptIn` below the KDoc.

## Rules
Small diffs, tests for each change, `gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by item with explicit paths (include this handoff in the first), never `.idea/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`. No schema change; if you think one is needed, stop and report. Report files, tests, decisions, skips.
