# Phase 2b — Early segmentation

Branch: `feat/phase-2b-segmentation`, created from `main` after 2a merges. Size: L. Parent: `docs/specs/build-plan.md` (phase 2b). Source spec: `docs/specs/tarry-mvp.md` §Segmentation, §Testing.

## Goal
Turn raw pings into visits, trips, places and gaps. This is a pure, golden-tested function in `:segmentation`, run by a WorkManager job, and shown in a crude day list, so the owner's dogfood days become readable and real days become regression fixtures.

## Non-goals
- The real timeline UI, cards and drawer (phase 3).
- Place management (phase 4).
- The map and the debug map overlay (phase 5).
- Summary maths (phase 7).
- Any editing that freezes records. 2b must *honour* frozen records and excluded ranges; there is no UI to create them yet.

## Carry-over
- Everything in `phase-2a.md` §Carry-over still applies.
- 2a provides:
  - `SegmentationTrigger` (currently a no-op), which this phase implements;
  - the raw CSV export format, used for fixtures;
  - `TrackingConfig`; segmentation parameters stay separate (below).
- `:segmentation` must not import Android or `:core-data` (`grep -r "import android" segmentation/` stays empty). `:app` maps between Room entities and segmentation types (AGENTS.md architecture).
- **Schema:** expected to stay unchanged. Segmentation-owned gaps are identified by cause (`NO_SESSION`, `NO_FIX`). Health-monitor gaps use the other causes, so no new column is needed. Any schema change means v2 plus a migration and a test (rule 4, `migration-guard`).

## Design notes
- **Entry point:** `Segmenter.segment(input: SegmentationInput, params: SegmentationParams): SegmentationResult`.
  - The input holds pings in the range, frozen visits and trips as fixed intervals, excluded ranges, known places (id, centroid, radius, merged/ignored flags), scheduled windows and sessions in the range.
  - The result holds stays (with matched place id or new-place centroid, confidence, bridged flag), trips (from/to stay refs, distance, mode), gaps (NO_SESSION / NO_FIX) and new places.
- **Parameters:** `SegmentationParams` defaults to the MVP spec table: stayRadius 60 m, minStayDuration 5 min, maxGapToBridge 45 min, placeMatchRadius 75 m, minTripDistance 150 m, shortTripMerge 3 min, overlap 30 min.
- **Stable ids (matters for future sync):** the glue reuses an existing unfrozen visit's UUID when a new stay overlaps it with the same place. Otherwise it creates a new one. Unfrozen detected rows no longer produced in the range are soft-deleted (`deleted = true`, `updatedAt` bumped), never hard-deleted.
- **Checkpoint:** after a run, advance it to `min(now, start of the last stay that may still be ongoing)`, so an ongoing all-day stay is re-evaluated on the next run. Each run loads from `checkpoint − overlap`.

## Tasks (in order)

### T1 — Model and params (claude, S)
Pure types and `SegmentationParams` in `me.tarry.segmentation`. Reuse `geo/Haversine.kt`.

### T2 — Synthetic trace generator (implementer, M)
- In `segmentation/src/test/` (or `testFixtures`).
- It builds seeded, deterministic traces: stays with configurable jitter and accuracy, drives and walks at set speeds and intervals, signal gaps, accuracy spikes, and multi-day and midnight-crossing ranges.
- Also add a CSV trace loader for the 2a export format.

### T3 — Stay detection and gap bridging (claude, M)
Grow the stay against a running accuracy-weighted centroid within `stayRadius`, and keep it if it lasts at least `minStayDuration`. Bridge gaps up to `maxGapToBridge` when the position on both sides is within `stayRadius`, and lower the confidence when a stay is bridged.

### T4 — Trips (implementer, S)
Everything between two stays becomes a trip, with distance summed from pings and the mode taken from the dominant activity (`IN_VEHICLE` → VEHICLE, `WALKING`/`ON_BICYCLE` → WALK, otherwise UNKNOWN).

### T5 — Merge (implementer, S)
Merge stays separated by a trip shorter than `minTripDistance`, or by a blip at the same place shorter than `shortTripMerge`.

### T6 — Places (claude, M)
- Match each stay to the nearest non-merged, non-deleted place within the place's own `radiusM` (or `placeMatchRadius`). Otherwise, emit a new place at the stay centroid.
- Ignored places still match, and are only hidden from totals later.
- Return updated centroids for matched places as a duration-weighted mean.

### T7 — Gaps (implementer, S)
- Scheduled-window time with no session → `NO_SESSION`.
- Time inside a session with no pings beyond `maxGapToBridge` that wasn't bridged → `NO_FIX`.
- Never emit a gap that overlaps a health-monitor gap from the input.
- Gaps are never visits.

### T8 — Frozen and excluded ranges (claude, M)
- Drop pings inside excluded ranges.
- Frozen visits and trips are fixed boundaries: output never overlaps them and never changes them.
- Only unfrozen time between them is regenerated.

### T9 — Golden and invariant tests (test-writer, M)
- **Golden cases** from the MVP spec:
  - a single site all day;
  - two sites with a drive;
  - a marina with two boats about 40 m apart, where shrinking the place radius splits them;
  - indoor drift with 100 m accuracy spikes;
  - a 40 min gap inside one stay (bridged);
  - a fuel stop under 5 min (part of the trip);
  - a quick return to the same site (merged);
  - a trip crossing midnight.
- **Invariants** over many seeded random traces:
  - no overlapping segments;
  - frozen records are untouched;
  - reprocessing is idempotent (running twice gives the same result);
  - overlap reprocessing equals a full run.

### T10 — SegmentationWorker glue (claude, L)
- **Location:** `app/.../segmentation/`. It's a `@HiltWorker`.
- **Each run:** load from the checkpoint, map entities to segmentation types, run, then reconcile using the stable-id rules. Then:
  - upsert visits, trips, gaps and places;
  - apply place defaults to *new* visits only (`defaultJobId` → `jobId`, `defaultBillable` → `billable`, `PlaceDefaultTag` → `VisitTag`);
  - update `Place.lastVisitAt`;
  - advance the checkpoint;
  - do all of it in one Room transaction.
- **Scheduling:** all runs go through one unique-work name, so two never overlap:
  - unique periodic work every 15 min while tracking (enqueued at window or manual start, cancelled at stop);
  - a one-time run from `SegmentationTrigger` at window end;
  - a run on app open;
  - a daily run that requires charging.
- **Debug:** a "Run segmentation now" button on the Sessions screen.

### T11 — Geocoder queue (implementer, S)
- `GeocodeWorker` (requires a network connection, exponential backoff) fills in `Place.address` where it is null.
- It runs only when `Geocoder.isPresent()`. On API 33+ it uses `getFromLocation(..., listener)`, and the blocking call on IO below that.
- The platform Geocoder is the only network use allowed besides tiles (rule 2).

### T12 — Crude day list (implementer, M)
- Replace the Timeline placeholder with a single day: today by default, with prev/next day buttons.
- Show visits, trips and gaps in time order:
  - visit: time range, duration, and the place name, address or coordinates;
  - trip: distance and mode;
  - gap: its cause.
- It's driven by the range `Flow`s. Use plain M3 components; phase 3 restyles it.
- Empty state: "No visits yet". Touch targets are at least 48dp, and content descriptions are set.

### T13 — Real-day fixtures (test-writer + owner, S)
- Convert the owner's 2a CSV exports into fixtures in `segmentation/src/test/resources/traces/`, with expected-output snapshots.
- **Privacy, because the repo is public:** first anonymise them. Translate every coordinate by a fixed offset to a synthetic origin (distances are kept) and shift times by a whole number of days. Have the owner review before committing.

## Acceptance criteria
- `gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` passes locally and in CI.
- All golden cases and invariants pass, and at least one anonymised real-day fixture is committed.
- `:segmentation` has no Android or core-data imports.
- **On the Pixel:** after a tracked day, the day list shows plausible visits and trips with no overlaps. Rerunning segmentation does not change visit ids. Places gain addresses when online.
- Room schema unchanged, or a v2 migration with a test.

## Risks and open questions
- **Parameter tuning:** expect to adjust `SegmentationParams` from real days. Changes need a golden-case update (rule 7).
- **Fixture anonymisation:** the default above needs owner approval before the first real trace is committed.
- **Geocoder:** it may be absent or rate-limited on some devices. Addresses stay null, and the day list falls back to coordinates.
