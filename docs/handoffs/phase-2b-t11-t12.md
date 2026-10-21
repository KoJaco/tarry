# Handoff: phase-2b-t11-t12

**From:** Claude (planner) → **To:** two implementer subagents (one per task) · **Date:** 2026-10-21 · **Branch/worktree:** `feat/phase-2b-segmentation` in `../Tarry-2b`

Both tasks share these constraints:
- AGENTS.md hard rules apply.
- No Room schema change; query-only DAO additions are fine. `git diff main -- core-data/schemas` must stay empty.
- Every behaviour change needs tests.
- Stage by explicit path, because the two tasks run at the same time in one worktree. Never use `git add -A`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Do not push.

---

## T11 — Geocoder queue

**Goal:** fill in `Place.address` with the platform Geocoder. Rule 2 allows this as the only network use besides map tiles.

**Files:**
- `core-data`: add `PlaceDao.getMissingAddress(limit: Int)`, which returns `deleted = 0 AND address IS NULL`, ordered by `lastVisitAt DESC`. Add a DAO test.
- `app/.../segmentation/`:
  - `PlaceGeocoder.kt`:
    - An `interface PlaceGeocoder { fun isAvailable(): Boolean; suspend fun lookup(lat: Double, lng: Double): GeocodeResult }`.
    - `GeocodeResult` is `Found(address: String)`, `NotFound` or `Failed(retryable: Boolean)`.
    - `AndroidPlaceGeocoder` uses `Geocoder(context, Locale.getDefault())` and `Geocoder.isPresent()`.
    - On API 33+, use `getFromLocation(lat, lng, 1, listener)` wrapped in `suspendCancellableCoroutine`, with `onError` → `Failed(true)`.
    - Below API 33, use the blocking call on `Dispatchers.IO`, with `IOException` → `Failed(true)`.
    - The address is `getAddressLine(0)`, else the non-null parts of thoroughfare, locality and adminArea joined with ", ". An empty result → `NotFound`.
    - Bind it in a Hilt module.
  - `GeocodeWorker.kt` (`@HiltWorker`):
    - If the geocoder is unavailable → `success()`, doing nothing.
    - Otherwise, for up to `TrackingConfig.geocodeBatchSize` (new, default 20) places with no address:
      - `Found` → save the address and `updatedAt = now`;
      - `NotFound` → save `address = ""` (empty string means "looked up, nothing found", so the place is never retried; document this on the DAO query and in the day list);
      - `Failed(retryable)` → stop the batch and return `retry()`.
    - Use `success()` when done.
  - `SegmentationScheduler.enqueueGeocode()`: unique one-time work `geocode` with `NetworkType.CONNECTED`, exponential backoff and `ExistingWorkPolicy.KEEP`. `SegmentationWorker` calls it after a successful run.
- **Tests:**
  - worker tests with a fake geocoder: Found, NotFound, Failed → retry, unavailable → no-op, and the batch limit;
  - a scheduler test for the constraint and the unique name;
  - the AndroidPlaceGeocoder address-formatting function, extracted as a pure function and unit-tested.

Commit: `feat(segmentation): geocode new places with the platform Geocoder (2b T11)`.

---

## T12 — Crude day list

**Goal:** replace the Timeline placeholder (`app/.../ui/timeline/TimelineScreen.kt`) with a single day of visits, trips and gaps. Phase 3 restyles it, so use plain M3 components with the theme.

**Files:**
- `app/.../ui/timeline/`:
  - `TimelineViewModel.kt`:
    - Hilt; `selectedDate: StateFlow<LocalDate>`, defaulting to today in `ZoneId.systemDefault()`, with `previousDay()` and `nextDay()`.
    - Day bounds are `[date 00:00, date+1 00:00)` in the system zone.
    - It `combine`s `TimelineRepository.observeVisits`, `observeTrips` and `observeGaps` for those bounds with `PlaceRepository.observeAll()`, using `flatMapLatest` on the date. That gives a `List<TimelineItem>` sorted by start.
    - `TimelineItem` is a sealed type:
      - `Visit(id, start, end, placeLabel)`, where placeLabel = place name, else a non-blank address, else "lat, lng" to 5 dp;
      - `Trip(id, start, end, distanceM, mode)`;
      - `Gap(id, start, end, cause)`.
  - `TimelineContent.kt`, stateless:
    - a header with the date plus Previous and Next `IconButton`s (with content descriptions and ≥48 dp);
    - a `LazyColumn` of rows:
      - visit: "09:12–11:40", the duration "2 h 28 min" and the place label;
      - trip: the time range, the distance ("1.2 km", or "850 m" under 1 km) and the mode (Vehicle / Walk / Unknown);
      - gap: the time range and a cause label (for example "No tracking session", "No location fix", "Tracking stopped", "App was closed", "Permission lost", "Location off").
    - Times are local `HH:mm` in the system zone.
    - Empty state: "No visits yet".
    - Each row merges its semantics into one readable description.
    - All strings go in `strings.xml`.
  - `TimelineScreen.kt` wires the ViewModel to the content.
- **Tests** (Robolectric Compose and JVM, in the style of `app/src/test/.../ui/`):
  - ViewModel: items combine and sort; the place label falls back from name to address to coordinates, and a blank address is skipped; changing the day changes the queried bounds; a DST day (Sydney 2026-10-04) has 23 h bounds.
  - Content: the empty state; one of each row type renders the expected text; the prev/next callbacks fire; the touch targets are ≥48 dp.

Commit: `feat(ui): crude day list of visits, trips and gaps (2b T12)`.

## Verification (each task)
```
cd ../Tarry-2b && ./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug
git diff main -- core-data/schemas   # empty
```
