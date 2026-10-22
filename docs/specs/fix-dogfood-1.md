# Fix — dogfood 1 (segmentation, capture, labels)

Branch: `fix/dogfood-1`, from `main`. Size: M–L. Source: the owner's first dogfood half-day (2026-10-22). The real export stays in `tmp/` and never enters the repo. Every test trace here is synthetic and only copies the *shape* of what happened.

## What went wrong (shapes, not data)
The true day was: home → drive (~11 min) → a marina, the boat berth about 65 m from the office (~2 h 50) → drive (~11 min) → home. The app showed 6 visits and 5 trips:

1. **Walk-in counted as driving.** After parking, there were 15 min of walking fixes (accuracy 30–98 m, hops of 30–270 m) within about 250 m of the berth. The stay only formed once accuracy settled, so the drive showed 28 min instead of 11, and its distance grew by about 1 km.
2. **One noisy fix ends a visit.** On the water, fixes hop 100–270 m every 20 s. A single hop outside the stay radius whose accuracy circle didn't reach the stay closed the stay (`StayDetector`), which split the marina into several visits.
3. **The merge test sums jitter.** `Merge.kt` keeps two stays separate when the *path* between them is ≥ `minTripDistanceM`. On noisy 20 s fixes, standing still sums to kilometres, so the app showed a "3.7 km · 40 min" drive with zero net displacement, and the same place appeared three times.
4. **IN_VEHICLE on a berthed boat.** The boat's rocking was classified as IN_VEHICLE. That made "trips" with no displacement, and it kept capture in MOVING mode (20 s fixes) for over an hour.
5. **Stale fixes.** Several fixes were identical to an earlier position, with accuracy exactly `maxAccuracyM` (100 m), saved minutes later as new pings. One of them anchored a phantom visit at a neighbouring address.
6. **Labels.** Reverse geocoding always returns the nearest street address, however far away it is. That's wrong for boats, bushland and remote sites.
7. **Stop radius.** The setting offers 100/150/200/300 m. The owner wants smaller options and a clear use case for each.

## Decisions (owner, 2026-10-22)
- **Travel stays clearly separate from visits.**
  - A **drive** runs from vehicle start to vehicle stop. Stops shorter than `minStayDurationMs` (traffic, lights) don't end it.
  - After the vehicle stops, movement within an **arrival radius** (new param, default 200 m) of the next stay belongs to that **visit**. The visit starts when the vehicle stops.
  - Walking that gets you clearly further away (net displacement beyond the arrival radius) becomes its own **on-foot trip** (`TripMode.WALK`), which means a trip can split into legs by mode. No schema change.
- **Movement needs displacement.** Activity recognition is a hint. Activity alone (IN_VEHICLE with no displacement) never makes a trip; sustained displacement with good accuracy makes one even without a moving activity.
- **One visit for places closer together than GPS can separate.** The office and berth are about 65 m apart. One marina visit is the target, and the owner splits it in the drawer (phase 3) when needed.
- **Stop radius options are 50 / 75 / 100 / 150 / 200 m**, each with a one-line use case. Anything below 100 m carries a reliability and battery warning (Android geofences are less reliable below about 100 m). The default stays at 100 m. A stored 300 m maps to 200 m.
- **Labels by distance to the address point:**
  - within 75 m: the street address;
  - within 300 m: "Near <address>";
  - further away: the suburb only, e.g. "Unnamed place · <locality>".

  "Name this place" in the drawer is phase 3, not this branch.
- **One PR** for all of the above.

## Tasks
### F1 — Segmentation (`:segmentation`, test-writer + implementer)
- **Sustained departure.** A stay closes only after ≥ `departureMinFixes` (2) consecutive fixes that are clearly outside it (`distance - accuracy > stayRadiusM`) *and* that span ≥ `departureMinMs` (2 min). Smaller excursions are outliers and are ignored. A silence longer than `maxGapToBridgeMs` still closes the stay as it does today.
- **Merge on net displacement.** In `Merge.kt`, replace the path sum with the maximum distance from stay A's centroid reached between the two stays, using fixes whose accuracy is ≤ `goodAccuracyM` (new, 50 m). If none qualify, use all fixes, accuracy-trimmed.
- **Arrival and departure.**
  - A stay's start moves back to the last vehicle-moving fix before it, when every fix in between is within `arrivalRadiusM` (200 m) of the stay centroid.
  - Departures work the same way in reverse: the stay ends at the first fix that leaves the arrival radius, or at the first vehicle fix.
  - A trip whose vehicle part ends with on-foot movement beyond the arrival radius splits into VEHICLE + WALK legs.
- **Displacement-gated trips.** A trip candidate with net displacement under `minTripDistanceM` merges into its neighbours, whatever the activity says.
- **Trip distance.** Sum only hops longer than the two fixes' combined accuracy, and skip fixes worse than `goodAccuracyM` when better ones exist.
- **Stale repeats.** A ping with the same lat/lng as an earlier ping and accuracy ≥ `maxAccuracyM` adds no position information. It still counts for "we were tracking" (no gap), but it neither starts, extends nor moves a stay centroid.
- **Params.** All new tunables go in `SegmentationParams`, with KDoc.
- **Golden cases** (synthetic, `TraceBuilder`):
  - `parkThenWalkIn`: drive, park, 15 min of noisy walking within 200 m, then a stay. The trip ends at parking, and the visit starts there.
  - `marinaJitter`: a 2 h stay with 20 s fixes hopping 100–270 m at 30–98 m accuracy, including two short IN_VEHICLE bursts with no displacement. Expect one stay and no trips.
  - `staleRepeats`: cached repeats at 100 m. No phantom stay.
  - `driveThenWalkAway`: a drive, then a sustained walk of 600 m. Expect VEHICLE + WALK legs.
  - `trafficStops`: a drive with three 2–4 min stops. Expect one trip.
  - The existing goldens stay green. Where a change is intended, the test explains it.

### F2 — Capture (`:app/tracking`, implementer; tracking-service change, plan reviewed here)
- **Demote MOVING without displacement.** In `CaptureModeMachine`, while MOVING, if the last `noProgressFixes` (6) accepted fixes stay within `noProgressRadiusM` (= the stay geofence radius) of their first fix, go back to STATIONARY. Do this even if the activity still reports IN_VEHICLE or WALKING. A fresh moving activity *transition* or a geofence exit re-enters MOVING. Tunables go in `TrackingConfig`.
- **Stale fixes.** Find out where the 100 m repeats come from: fused cached fixes, `getLastLocation`, or `Location.time` older than the request. Reject in `PingFilter` any fix whose `Location.time` (or `elapsedRealtimeNanos`) isn't newer than the last accepted fix. Store the fix time, not the receipt time, if they differ. Unit tests.
- Tests: `CaptureModeMachine` cases for berthed-boat IN_VEHICLE (demotes) and real driving (stays MOVING through a 3 min traffic stop).

### F3 — Settings and labels (`:core-data` settings, `:app`, implementer)
- `Settings.ALLOWED_STAY_RADII_M = 50, 75, 100, 150, 200`. A stored 300 maps to 200. Each option in the radius sheet gets a one-line use case, with a warning line for 50 and 75 m. Settings tests.
- `PlaceGeocoder.lookup` returns the address point's distance from the query when `Address.hasLatitude()`. `GeocodeWorker` stores the label by the distance rule above. When the distance is unknown, it keeps today's behaviour. Unit tests use a fake geocoder.
- **Re-run detection after an upgrade.** Add `SEGMENTATION_VERSION`, stored in DataStore. When it is behind, lower `SegmentationCheckpoint.processedUntil` to the start of the retention window and enqueue a run. Frozen records stay untouched, as always. Test this.

### F4 — Review
- Reviewer: check against this spec and AGENTS.md.
- On the Pixel: install with `install -r`. The upgrade re-run re-segments the dogfood day, and the owner compares it with the true day above.

## Acceptance
- `./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` passes. The Room schema is unchanged.
- The new golden cases pass, and the existing ones pass or have their intended changes explained.
- On the dogfood day after the upgrade re-run, the owner sees: drive (~11 min) → one marina visit → drive (~11 min) → home.
