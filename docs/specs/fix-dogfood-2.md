# Fix: dogfood day 2 (tracking accuracy and segmentation noise)

Branch `fix/dogfood-2`, stacked on `feat/phase-4b-places`. Source: owner dogfood 2026-10-22 (real trace in gitignored `tmp/dogfood-session-2`, never committed). Approved 2026-10-23.

## Context
On 2026-10-22 the owner dogfooded a full boat-work day. The timeline came out with about 25 fragments where it should have had about 14 segments.

**What the trace shows** (`tmp/dogfood-session-2`, 716 pings, analysed locally):
- **Network-only fixes.** Away from the drives, almost every fix is network-grade (50–100 m). Only 4 of 716 pings carry a speed.
- **The marina day jumps between a few anchor points.** For 7 h the phone alternated every 20–60 s between a handful of exact, repeating coordinates 200–500 m apart. One of them is a 20 m Wi-Fi fix that repeated 60+ times.
- **Spikes.** There are single-fix spikes 3–8 km away that return within 1–2 min (09:48, 16:35).
- **Every ping says STILL**, including the drives.
- **Tracking stopped at 17:00** while the owner was still on the boat; they restarted it manually at 17:10.

**Root causes (from the code audits):**
1. **GPS is never requested.** `LocationPriority` has only PASSIVE, LOW_POWER and BALANCED (`app/.../tracking/TrackingConfig.kt:3,20,35`; `PlayLocationClient.kt:40-44`).
2. **Capture gets stuck in MOVING.** The stationary safety net exits on any two "outside" fixes, and the ways back (no-progress run, 50 m anchor) reset on every anchor jump. So capture ran at 20 s and saved every jump (`CaptureModeMachine.kt:127-166`).
3. **The activity column is just the last Activity Recognition (AR) transition.** It has no expiry, no timestamp check, no logging and no fallback (`CaptureCoordinator.kt:84,253`; `TrackingReceivers.kt:35-42`). One STILL arrived per session and nothing after it.
4. **Segmentation has no defence against spikes or repeated anchors:**
   - exact repeats are dropped only at ≥ 100 m accuracy (`Segmenter.kt:94-101`);
   - a stay closes on two outside fixes even when those two disagree with each other (`StayDetector.kt:56-72`);
   - places use fixed 75 m / 120 m scales, so each anchor point becomes its own visit and its own geocoded place, and those places then stop later merges (`Places.kt:32-36,182-186`; `Merge.kt:43,57-65`).
5. **Short trips are probably invisible on the canvas.** The 7:24 walk and the 7:51 drive are clean in the data, but no trip row shows between the two Home visits or before Site A. The replay below confirms this one way or the other.

**Owner decisions (2026-10-23):**
- **Order:** finish 4b first, then the dogfood fixes.
- **The 7:24 loop was a real walk.** Showing Home → walk → Home is correct.
- **On the boat the phone was in a cabin or bag.**
- **The lunch walk** shows as its own walk and visit only when GPS is good enough. Otherwise the boat visit stays whole and the owner splits it by hand.
- **Window end mid-work:** keep tracking until a stay of at least 10 min is confirmed, with a 2 h cap.
- **GPS:** accuracy first. Use high-accuracy location throughout every session and optimise battery later, based on measurements.

**Ground truth for the day:**
| Time | What happened |
|---|---|
| 07:00–07:24 | Home |
| 07:24–07:31 | Walk |
| 07:31–07:50 | Home |
| 07:50–08:01 | Drive |
| 08:01–08:17 | Site A (boatyard) |
| 08:17–08:45 | Boat out and back |
| 08:45–09:45 | Site A (boatyard) |
| 09:45–09:58 | Boat transit |
| 09:58–12:45 | Marina B |
| 12:45–13:10 | Lunch walk to a nearby park |
| 13:10–17:00 | Marina |
| 17:10–17:50 | Boat back, via the mooring |
| 17:50–18:00 | Site A (boatyard) |
| 18:00–18:15 | Drive |
| 18:15–18:30 | Home |

## Part B: dogfood fixes (branch `fix/dogfood-2`, from `feat/phase-4b-places`; both touch the runner)

### B0. Replay harness (test-writer, S). Goes first; everything else is measured against it
- Add an **opt-in** JVM test in `segmentation/src/test`. It runs only when `-Ptarry.replay=<path to csv dir>` is set, loads the CSVs with the existing `CsvTraceLoader`, runs `Segmenter`, and prints the segment list (start, end, kind, place cluster, distance, mode).
- **The real trace never enters the repo.** It stays in the gitignored `tmp/`, per the memory "No real traces in repo".
- Also replay the capture side: feed the CSV through `PingFilter` and `CaptureModeMachine` to see which fixes would be kept and which mode capture would sit in.
- Record the baseline output in the PR description only, never in a committed file.

### B1. Capture: accuracy first, battery later (implementer + test-writer, M)
The owner's direction (2026-10-23) is to get the most accurate capture possible now, whatever the battery cost, and optimise once the data is right. Battery tuning becomes a later, measured task.
- **GPS all the time while a session runs.**
  - Add `HIGH_ACCURACY` to `LocationPriority` and map it to `PRIORITY_HIGH_ACCURACY` in `PlayLocationClient.kt`.
  - **Both modes use it.** MOVING takes a fix every 10 s. STATIONARY takes one every 60 s instead of 3 min, using `setMinUpdateIntervalMillis` to match.
  - **No batching:** `maxUpdateDelay` is 0.
  - **Stale fixes:** `setWaitForAccurateLocation(true)` and `setMaxUpdateAgeMillis(0)`, so stale cached network fixes aren't delivered as new.
  - All of this lives in `TrackingConfig`, so a later battery pass can turn it back down.
- **The capture mode now only sets the interval.** Mode thrash no longer loses accuracy, so leave the stationary and moving rules alone apart from one fix: exact repeats of a recent coordinate never count as exit evidence (`CaptureModeMachine.kt:127-136`).
- **Capture keeps every fix.** Drop the MOVING `DROP_DISPLACEMENT` (25 m) and the STATIONARY jitter drop in `PingFilter`. Keep only the 100 m accuracy cap and the 70 m/s cap. Segmentation (B2) decides what's noise, so the raw trace keeps the most information for replays.
- **Spikes at capture.** If a fix is accepted and the next fix returns within `spikeReturnM` of the fix before it, the spike stops being the comparison point (`PingFilter.kt:60`). The spike ping is still saved; segmentation handles it in B2.
- **AR diagnostics.**
  - The receiver uses the event's `elapsedRealtimeNanos` and ignores events older than `arStaleMs`.
  - Each transition is logged with its timestamp to the existing debug or sessions surface, as a count plus the last event.
  - When a GPS fix shows speed ≥ `movingSpeedMps`, mark the ping's activity as inferred moving (a new `IN_VEHICLE`-or-unknown rule kept in `TrackingConfig`; no schema change, since `activity` is already nullable).
  - The goal is to find out on the next dogfood day why IN_VEHICLE never arrived, not to guess at it now.
- **Window-end grace.** At schedule end, if capture is MOVING or the last confirmed stay started less than 10 min ago, keep the session open until a stay of at least 10 min is confirmed (cap `windowEndGraceMaxMs` = 2 h), then stop with `SCHEDULE_END`. The notification shows "Finishing up…". Changes go in `TrackingController.kt:236-240`, with a test.
- **Tests:** `CaptureModeMachineTest` and `PingFilterTest` cases built from the day's patterns:
  - alternating anchors don't keep MOVING alive past 5 min;
  - a spike doesn't trigger an exit;
  - a GPS-confirmed exit works;
  - window-end grace, both in the stay case and in the moving case.

### B2. Segmentation: clean the stream, then require coherent departures (implementer + test-writer, L)
- **Pre-pass in `Segmenter.kt`, before `detectStays`:**
  - **Spike rejection.** Drop a fix that is more than `spikeMinM` (500 m) from both neighbours while those neighbours agree within their combined accuracy, or whose implied speed in and out is above `maxPlausibleSpeedMps`.
  - **Anchor-aware repeats.** Any exact coordinate seen at least `anchorMinRepeats` times inside a window is an anchor observation. Its effective accuracy is raised to at least the spread of anchors in that window, so it stops pinning the centroid (`StayDetector.kt:81-84`).
  - **Robust position for low-accuracy fixes.** Fixes worse than `goodAccuracyM` get a windowed geometric-median position (±`smoothWindowMs`, 3 min) for stay detection. Good fixes pass through unchanged, so real GPS walks and boat trips survive.
- **Coherent departure (`StayDetector.kt:56-72`).** The fixes that close a stay must agree with each other (within combined accuracy) or progress steadily away from the centroid. Ambiguous fixes no longer reset a departure that is progressing (the slow-boat case).
- **Places and merge scale with noise.**
  - A new place's radius and the matching radius for new places become `max(placeMatchRadiusM, k × the stay's robust spread)`, capped at 300 m.
  - `indistinguishablePlaces` and `distinctKnownPlaces` use the same accuracy-aware scale.
  - `Merge.netDisplacement` uses a robust displacement (the 80th-percentile fix), not the single furthest one.
- **Every new tunable goes in `SegmentationParams`** (AGENTS rule 5). Bump `SEGMENTATION_VERSION` so unfrozen history re-runs; frozen records are untouched.
- **Tests:**
  - Add `TraceBuilder.anchors(points, accuracies, intervalJitter)` and `spikes(..., returnAfter)`.
  - Add golden case `boatDayNetworkAnchors`, a synthetic look-alike of the day (fake coordinates): a 7 h marina at three alternating anchors plus spikes gives one stay and one place.
  - Add golden case `slowBoatLoopGoodGps`: a 1 km loop at 2 m/s with GPS-grade fixes gives stay → trip → stay.
  - All existing golden cases stay green, or any change to them is justified one by one in the PR.

### B3. Canvas: short trips visible, honest mode icon (implementer, S)
- Confirm with the replay. If trips under about 15 min have no row, give every trip a minimum-height row: a compact "Walk · 7 min" / "Drive · 11 min" chip, at least 48 dp tall to tap, overlapping the hairline.
- A trip with mode UNKNOWN shows a neutral "route" icon, not the car. The 12:23 "trip" that showed a car was the lunch walk.
- Files: `ui/timeline/DayCanvas.kt` and the trip block rendering. Compose test.

### B4. Review and device check (reviewer, ui-reviewer, deployer, S)
- The reviewer checks against this plan and AGENTS.md.
- The deployer installs the build. The owner dogfoods day 3 and re-exports to `tmp/dogfood-session-3` for the next replay.

## Verification
- `gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` passes, with the schema unchanged.
- **Local replay of the real day** (`gradlew.bat :segmentation:test -Ptarry.replay=tmp/dogfood-session-2 --tests *Replay*`) needs to show:
  - Marina B 09:58–17:00 as **one** place, made of at most 3 visits (the lunch walk may or may not show; no other splits);
  - Site A as one place;
  - trips for the 07:24 walk, the 07:51 drive, the 08:17 boat loop (if it's detectable at that accuracy, otherwise noted), the 09:45 transit and the evening legs;
  - no place created at a spike location;
  - about 14 to 18 segments in total.
- **Capture:** the day-3 export should show mostly GPS-grade fixes with speed on deck and on the drives. In the cabin, network fixes are still expected, and B2 deals with them. Note the battery drain over the day as the baseline for the later optimisation pass.
- **On device:** a day-3 dogfood with GPS in MOVING, the AR log showing whether IN_VEHICLE fires, and window-end grace covering a late finish.
