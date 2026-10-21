# Handoff: phase-2b-t2-traces

**From:** Claude (planner) → **To:** implementer subagent · **Date:** 2026-10-21 · **Branch/worktree:** `feat/phase-2b-segmentation` in `../Tarry-2b`

## Goal
Phase 2b T2 (`docs/specs/phase-2b.md`): add deterministic test fixtures for segmentation. These are a seeded synthetic trace builder plus a loader for the phase 2a raw CSV export, so the T3–T9 algorithm and golden tests can be written against them.

## Constraints
- Test code only: everything lives in `segmentation/src/test/kotlin/me/tarry/segmentation/trace/`. Do not add a `testFixtures` plugin. `:app` does not need these.
- Pure Kotlin/JVM with JUnit 4 (already on the classpath). No Android, `:core-data` or new dependencies.
- Use the types in `segmentation/src/main/kotlin/me/tarry/segmentation/Model.kt` (`Ping`, `Activity`, `TimeRange`) and `geo/haversineMeters`. Do not change main sources.
- Out of scope: the segmentation algorithm itself, golden cases and real fixtures.

## What to build
1. **`TraceBuilder`**, a fluent, seeded builder (`java.util.Random(seed)`). It is deterministic: the same seed and calls give identical pings. It starts at a lat/lng and an epoch-ms start time and keeps a cursor (time and position). Operations:
   - `stay(durationMs, intervalMs = 60_000, jitterM = 10.0, accuracyM = 20f, activity = Activity.STILL)`: pings around the current point. Jitter is a random offset with a uniform direction and a radius uniform in 0..jitterM. Accuracy gets ±25 % noise.
   - `move(toLat, toLng, speedMps, intervalMs = 20_000, mode = Activity.IN_VEHICLE, accuracyM = 25f)`: a straight-line path at constant speed, ending exactly at the target, which becomes the new cursor point.
   - `moveBy(bearingDeg, distanceM, speedMps, …)`: a convenience that computes the target with a destination-point formula.
   - `gap(durationMs)`: advances time with no pings.
   - `spikes(count, accuracyM = 100f, offsetM = 150.0)`: ping outliers interleaved at the current point (indoor drift).
   - `session(id)`: sets the `sessionId` stamped on later pings (default 1).
   - `build(): List<Ping>`, sorted by time.
   - Also `fun Trace.range(): TimeRange` or an equivalent, so a test can tell what time the trace covers.
2. **Helpers:**
   - `offsetMeters(lat, lng, northM, eastM)`, a local flat-earth offset that is fine under 50 km;
   - a constant `SYDNEY_ORIGIN` (any harbour-side coordinate);
   - `atLocal(date, time, zone = "Australia/Sydney")` returning epoch ms, for multi-day and midnight-crossing traces.
3. **`CsvTraceLoader`:**
   - It parses the 2a export (header `id,timestamp_utc,zone_id,lat,lng,accuracy_m,speed_mps,bearing,activity,source,session_id`, see `app/src/main/java/me/tarry/app/export/RawPingFormats.kt` on `main`) from a classpath resource path into `List<Ping>`.
   - Empty activity → null. An unknown activity name → `Activity.UNKNOWN`.
   - `timestamp_utc` is ISO-8601 with `Z`.
   - Ignore the speed, bearing and source columns.
   - Fail with a clear message on a header mismatch.
4. A tiny synthetic CSV at `segmentation/src/test/resources/traces/synthetic-sample.csv` (5 rows, made-up coordinates near `SYDNEY_ORIGIN`, **not** real data) for the loader test.

## Tests (required) in `.../trace/TraceBuilderTest.kt` and `CsvTraceLoaderTest.kt`
- The same seed gives identical output, and a different seed gives different output.
- Every stay ping is within `jitterM` of the centre (haversine).
- `move` ends at the target within 1 m, consecutive pings are `intervalMs` apart, and the implied speed is within 5 % of `speedMps`.
- `gap` leaves no pings in the gap interval.
- A midnight-crossing build (23:30 → 00:30 Sydney) is in time order.
- `offsetMeters` round-trips against haversine within 0.5 %.
- The loader reads the sample (count, first timestamp, null activity) and rejects a bad header.

## Verification
```
cd ../Tarry-2b && ./gradlew.bat :segmentation:test
grep -r "import android" segmentation/   # must be empty
```

## Commit
One commit on `feat/phase-2b-segmentation`: `test(segmentation): seeded trace builder and CSV loader (2b T2)`. The message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Include this handoff file. Do not push.
