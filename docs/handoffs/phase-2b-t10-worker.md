# Handoff: phase-2b-t10-worker

**From:** Claude (planner) → **To:** implementer subagent · **Date:** 2026-10-21 · **Branch/worktree:** `feat/phase-2b-segmentation` in `../Tarry-2b`

## Goal
Phase 2b T10 (`docs/specs/phase-2b.md`): run `Segmenter.segment` from WorkManager, reconcile its output with the Room rows using stable ids, and schedule the runs. After this, a tracked day produces visit, trip, gap and place rows.

## Constraints
- AGENTS.md hard rules apply.
- **No schema change.** Query-only DAO additions are fine; check that `core-data/schemas/.../1.json` stays byte-identical.
- No network calls (geocoding is T11).
- `:segmentation` stays free of Android and `:core-data` imports. All mapping lives in `:app`.
- Every tunable comes from `SegmentationParams` (segmentation) or `TrackingConfig` (scheduling periods), not literals.
- **Out of scope:**
  - GeocodeWorker (T11);
  - the day list (T12);
  - any change to `:segmentation` main sources. If you find a segmentation bug, stop and report it.

## Files
- **`core-data`, query-only DAO additions with tests in the existing DAO test style:**
  - `PlaceDao.getAllActive()`: `deleted = 0`.
  - `VisitDao.durationByPlaceOutside(from, to): List<PlaceDuration>`: the sum of `end - start` for non-deleted visits with a placeId that do **not** overlap `[from, to)`, grouped by placeId. Use a small result data class.
  - `VisitDao.getUnfrozenInRange(from, to)` and `TripDao.getUnfrozenInRange(from, to)`, plus "frozen in range" variants. A trip has a `frozen` column like visit; check the entity.
  - The `gap` cause filter is done in Kotlin; no new query is needed.
- **`core-data`, `SegmentationStore`:** a class with a `suspend fun <T> transaction(block: suspend () -> T): T` built on `RoomDatabase.withTransaction`. Add room-ktx only if it is missing from the catalog; check first. Bind it in `DatabaseModule`.
- **`app/.../segmentation/`, new package:**
  - `SegmentationMapper.kt`: entity ↔ segmentation types.
    - `ActivityType` → `Activity`, `TripMode` ↔ `TripMode`.
    - `GapCause` NO_SESSION/NO_FIX ↔ the segmentation `GapCause`.
    - All other `GapCause` values → `healthGaps`.
  - `SegmentationRunner.kt`: a `@Singleton`. `suspend fun run(nowMs: Long)` is guarded by a process-wide `Mutex`, so two runs never overlap even across workers.
  - `SegmentationWorker.kt`: a `@HiltWorker` that calls the runner. On an exception, return `Result.retry()` at most 3 times (`runAttemptCount`), then `failure`.
  - `SegmentationScheduler.kt`: WorkManager wiring (below).
  - `WorkManagerSegmentationTrigger.kt`: replaces `NoOpSegmentationTrigger`.
- **`app/.../tracking/SegmentationTrigger.kt`:**
  - add `suspend fun onCaptureStarted()` and `suspend fun onCaptureStopped()` next to `onWindowEnd()`;
  - keep the no-op class for tests, implementing all three;
  - in `TrackingController`, call `onCaptureStarted()` right after a successful `startService(...)` and `onCaptureStopped()` in `stopCapture(...)`. Keep this diff minimal, because another branch also edits this file.
- **`app/.../MainActivity.kt` `onStart`:** also enqueue a one-time run.
- **`app/.../ui/sessions/`:** a "Run segmentation now" button (≥48 dp, a string resource) that enqueues a one-time run.
- **`TrackingConfig`:** `segmentationPeriodMin: Long = 15`, plus the daily-run period of 24 h.

## Runner algorithm (`run(nowMs)`)
1. **Range.**
   - `checkpoint = checkpointDao.processedUntil`.
   - If it is null, use the earliest session start. If there are no sessions, save the checkpoint as `nowMs` and return.
   - `from = checkpoint - params.overlapMs`, `to = nowMs`.
   - Then widen `from` down to the earliest `start` of any **unfrozen**, non-deleted visit or trip overlapping `[from, to)`. Do this once, not in a loop, so a detected row is never half inside the range.
2. **Load:**
   - pings in `[from, to)`;
   - sessions overlapping it, with an open session's end set to `nowMs`;
   - frozen visits and trips overlapping it → `frozen`;
   - excluded ranges → `excluded`;
   - gaps in range whose cause is not NO_SESSION/NO_FIX → `healthGaps`;
   - all active places, with `weightMs` from `durationByPlaceOutside(from, to)`, plus `merged = mergedIntoId != null` and `ignored`;
   - windows = `ScheduleWindowChecker.activeIntervals(from, to)` mapped to `TimeRange`.
3. **Segment:** `Segmenter.segment(input, SegmentationParams())`.
4. **Reconcile** inside one `SegmentationStore.transaction { }` with `now = nowMs`:
   - **Places:**
     - Each `NewPlace` → `Place(id = UUID, centroid, radiusM = params.placeMatchRadiusM.toFloat(), updatedAt = now)`.
     - Each `PlaceCentroidUpdate` → a copy of the place with the new centroid and `updatedAt = now`, only if the place moved by more than 1 m.
     - Resolve every stay's `PlaceRef` to a place id.
     - For each place with stays, set `lastVisitAt = max(old, last stay end)`.
   - **Visits:**
     - Candidates are the existing **unfrozen** non-deleted visits overlapping `[from, to)`.
     - For each stay in order, reuse the id of the candidate with the largest time overlap and the **same placeId** that has not been reused yet. Otherwise use a new UUID.
     - A reused row keeps its user-irrelevant fields; write start, end, placeId and confidence, with `updatedAt = now`, **only if something changed**.
     - New rows: `origin = DETECTED`, `zoneId` = the zone of the latest ping at or before the stay start (falling back to the first ping's zone), `frozen = false`.
     - **Place defaults apply to new visits only:** `jobId = place.defaultJobId`, `billable = place.defaultBillable`, and a `VisitTag` for each `PlaceDefaultTag`.
     - Unreused candidates → `deleted = true, updatedAt = now` (soft delete; never hard-delete).
   - **Trips:** the same approach, reusing by the largest overlap only. Set `fromVisitId`/`toVisitId` from the resolved stay ids, null for null indices. Use `distanceM.toFloat()` and the mapped mode. Unreused → soft-deleted. Do not touch billable or attributedJobId on reused rows.
   - **Gaps:** existing NO_SESSION/NO_FIX gaps overlapping the range are reused by the largest overlap with the **same cause**. Unreused → soft-deleted. Health-monitor gaps are never touched. A new gap's `sessionId` is null.
   - **Checkpoint:**
     - if any stay has `mayBeOngoing`, the start of the last such stay;
     - else if the last trip has `toStay == null` and ends at the last ping, the start of that trip;
     - else `nowMs`.

     Store `min(that, nowMs)`.

## Scheduling (`SegmentationScheduler`)
- **`enqueueNow()`:** one-time unique work `segmentation-now`, `ExistingWorkPolicy.KEEP`.
- **`startPeriodic()`:** `segmentation-periodic`, every `segmentationPeriodMin`, with `ExistingPeriodicWorkPolicy.KEEP`.
- **`stopPeriodic()`:** cancels it.
- **`ensureDaily()`:** `segmentation-daily`, every 24 h, `setRequiresCharging(true)`, KEEP. Call it from `Application.onCreate` or wherever `HealthCheckWorker` is scheduled; follow that pattern.
- Overlap is prevented by the runner's Mutex, not by work names. Note this in KDoc as a deliberate deviation from "one unique-work name", because WorkManager cannot share one name between periodic and one-time work.
- **Trigger mapping:**
  - `onWindowEnd` → `enqueueNow()`;
  - `onCaptureStarted` → `startPeriodic()`;
  - `onCaptureStopped` → `stopPeriodic()` + `enqueueNow()`.

## Tests (required)
- **`SegmentationRunnerTest`:** Robolectric with an in-memory Room DB, as the 2a DAO and repository tests do, plus a fake window checker. Cover:
  - a two-site day creates 2 visits, 1 trip and 2 places, and advances the checkpoint;
  - **a re-run with the same data keeps the same visit and trip ids and writes no `updatedAt` change (stable ids);**
  - more pings later extend the ongoing visit under the same id;
  - a frozen visit is untouched, and nothing overlaps it;
  - a visit that disappears is soft-deleted, not removed;
  - place defaults (job, billable, tags) are applied to a new visit at a known place, and not re-applied when a reused visit's job was cleared;
  - health gaps are untouched;
  - with no sessions, it returns after setting the checkpoint.
- Mapper unit tests for the enums.
- **Scheduler:** use `WorkManagerTestInitHelper` (work-testing; add it as a test dependency if absent) to check the unique names, the KEEP policy, the charging constraint, and that cancel stops periodic work.
- **Controller:** `onCaptureStarted` and `onCaptureStopped` are called. Extend the existing TrackingControllerTest fake trigger.
- **Sessions UI:** the button exists and calls its callback.

## Verification
```
cd ../Tarry-2b && ./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug
git diff main -- core-data/schemas   # must be empty
```

## Commits
Small and logical: core-data queries + store, runner + mapper, worker + scheduler + trigger wiring, UI button. Each message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Stage by explicit path, because a test-writer may be committing segmentation tests in the same worktree. Do not push.
