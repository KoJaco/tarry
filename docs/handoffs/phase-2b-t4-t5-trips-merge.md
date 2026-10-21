# Handoff: phase-2b-t4-t5-trips-merge

**From:** Claude (planner) → **To:** implementer subagent · **Date:** 2026-10-21 · **Branch/worktree:** `feat/phase-2b-segmentation` in `../Tarry-2b`

## Goal
Phase 2b T4 (trips) and T5 (merge) from `docs/specs/phase-2b.md`, built as internal pure functions next to `StayDetector.kt`. `Segmenter` (later) will chain detect → merge → trips.

## Constraints
- `:segmentation` only. Pure Kotlin with no Android or `:core-data` imports, and no new dependencies.
- Do not change `Model.kt`, `SegmentationParams.kt` or `StayDetector.kt` beyond what is listed below.
- Use `geo/haversineMeters`. Every threshold comes from `SegmentationParams`, not a literal.

## T5 — `Merge.kt`
`internal fun mergeStays(stays: List<StayCandidate>, params: SegmentationParams, distinctPlaces: (StayCandidate, StayCandidate) -> Boolean = { _, _ -> false }): List<StayCandidate>`

- Input stays are in time order, as `detectStays` returns them. Repeatedly merge each adjacent pair (a, b) until no pair qualifies. Chains collapse: A+B+C becomes one stay.
- Merge (a, b) when `!distinctPlaces(a, b)` and either:
  1. the path distance between them is `< params.minTripDistanceM`. The path distance is the haversine sum a.centroid → each ping strictly between `a.range.endMs` and `b.range.startMs` (taken from a `between` list, see below) → b.centroid; or
  2. the gap `b.range.startMs - a.range.endMs < params.shortTripMergeMs` **and** the centroids are within `params.placeMatchRadiusM` (a blip at the same place).
- To get the pings between the stays, the function also takes `pings: List<Ping>` (all pings of the stretch, time-ordered). The final signature is `mergeStays(stays, pings, params, distinctPlaces)`.
- The merged stay has:
  - range `a.start..b.end`;
  - pings = a.pings + the in-between pings within `stayRadiusM` of either centroid + b.pings, time-ordered;
  - its centroid recomputed with the same accuracy-weighted rule as StayDetector. Extract a small shared `internal fun weightedCentroid(pings): Pair<Double, Double>` into StayDetector.kt and use it in both places, without changing StayDetector behaviour;
  - `bridged = a.bridged || b.bridged`, `open = b.open`.
- `distinctPlaces` exists for T6, where two stays that match different known places (the marina case) must never merge. Keep it as a parameter.

## T4 — `Trips.kt`
`internal fun buildTrips(stays: List<StayCandidate>, pings: List<Ping>): List<TripCandidate>`, where `internal data class TripCandidate(val range: TimeRange, val fromStay: Int?, val toStay: Int?, val distanceM: Double, val mode: TripMode)`.

- Input stays come from mergeStays, in time order; indices refer to that list.
- For each adjacent pair (i, i+1): a trip over `[stays[i].range.endMs, stays[i+1].range.startMs]`.
  - Its distance is the haversine sum stay i centroid → pings strictly inside the range → stay i+1 centroid.
  - It has `fromStay = i` and `toStay = i + 1`.
- **Leading pings** before the first stay form a trip `fromStay = null` over first ping .. first stay start. Its distance is the ping path plus the last leg to the stay centroid. This applies only when there are at least 2 such pings, or 1 ping plus the stay.
- **Trailing pings** after the last stay (only possible if the caller drops a short open candidate) form a trip `toStay = null` in the same way.
- **No stays and ≥2 pings:** one trip from null to null over all pings.
- **Mode:** the dominant activity among the trip's inside pings by count. Ignore null, STILL and UNKNOWN. IN_VEHICLE → VEHICLE, WALKING or ON_BICYCLE → WALK. Ties go to VEHICLE. With no qualifying activity → UNKNOWN (no speed-based guessing; the spec says activity only).
- A zero-duration trip (stay end == next stay start) is still emitted with distance = centroid-to-centroid.

## Tests (required), using `trace/TraceBuilder`
- **MergeTest:**
  - two stays 100 m apart with a 100 m walk between → merged (rule 1);
  - two stays 400 m apart → not merged;
  - quick return: stay, 2 min away and back over a 500 m path, stay at the same spot → merged (rule 2);
  - a 2 min blip, but the second stay is 300 m away → not merged;
  - a 3-stay chain where all pairs qualify → 1 stay;
  - `distinctPlaces = { _, _ -> true }` blocks a merge that would otherwise happen;
  - the merged centroid and range are correct, and `open`/`bridged` propagate.
- **TripsTest:**
  - two stays with a 5 km drive → one trip, distance within 2 % of 5 km, mode VEHICLE (the builder's default `IN_VEHICLE`);
  - a walk → WALK;
  - null activities → UNKNOWN;
  - leading pings → fromStay null;
  - no stays → one null/null trip;
  - a mixed vehicle+walk trip → the majority wins.

## Verification
```
cd ../Tarry-2b && ./gradlew.bat :segmentation:test && grep -r "import android" segmentation/   # grep must be empty
```

## Commit
Two commits on `feat/phase-2b-segmentation`: `feat(segmentation): merge stays (2b T5)` (including this handoff file) and `feat(segmentation): trips between stays (2b T4)`. Each message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.
