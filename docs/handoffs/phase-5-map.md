# Handoff: phase 5 — Map

**From:** Claude (planner, session `tarry-phase-4`) → **To:** next Claude session (planner first, then subagents) · **Date:** 2026-10-24 · **Branch to create:** `feat/phase-5-map` from `main`

## Where things stand
- **`main` at `4987670`** has phases 0–4 and the dogfood fixes:
  - phase 4 is #14 (selection, jobs and tags) and #15 (places);
  - the dogfood fixes are in #16.

  All of it passes CI, and the DB is still Room v1.
- **Dogfood day 3 (2026-10-24) looks good to the owner.**
  - Capture runs on high-accuracy GPS for the whole session (owner direction: accuracy first, battery tuning later, based on measurements).
  - Segmentation now handles network noise.
  - Walks stay in their trips, and trips come out WALK or VEHICLE (car and boat share one icon).
  - The timeline is a true time scale at 144 dp/h, with 15-minute ticks.
  - The owner will report "some tiny tweaks" later. Ask for them; they aren't recorded yet.
- **Read these first:**
  - `AGENTS.md`;
  - `docs/specs/build-plan.md`, the phase 5 line;
  - `docs/specs/tarry-mvp.md` § Map view, § Edit drawer (header mini map), § Settings → Places (radius with map preview) and § Settings → Maps;
  - `docs/specs/phase-4.md`;
  - `docs/specs/fix-dogfood-2.md`;
  - `docs/design/tokens.md`.

## Goal
Add a map that makes the day's record easy to check at a glance. That means a Map view of the selected day, with:
- the route polyline;
- numbered visit pins;
- a time scrubber;
- a date-time jump.

The map also goes in four other places:
- the drawer header, as a mini map with the place radius;
- a live map preview in the Places radius sheet;
- a map pin as a way to place a manual visit;
- offline regions, so the owner's work area works without signal.

## Scope (build plan phase 5 plus the MVP spec)
1. **MapLibre Native + OpenFreeMap.**
   - Add MapLibre Native for Android to `gradle/libs.versions.toml`. Look up the current stable version; never guess it.
   - The style is OpenFreeMap vector tiles, themed light and dark from `docs/design/tokens.md` (map style JSON colours come from tokens, not literals).
   - Attribution is required: OpenFreeMap / OpenMapTiles / OSM.
2. **Map view for a day.**
   - The route polyline comes from that day's raw pings. Trips store no geometry.
   - Visit pins are numbered in time order and coloured by job.
   - Gaps show as breaks in the line.
   - Tapping a pin opens the existing edit drawer (`EditTarget`).
3. **Scrubber** (MVP § Map view).
   - A horizontal strip of blocks in proportion to time: visits in job colour, trips grey, gaps hatched.
   - Dragging moves a marker along the route and centres the map, with a time label above the thumb.
   - On release it snaps to visit starts.
   - Tapping the time label opens a date-time jump.
   - WCAG 2.5.7: it needs a non-drag alternative, such as step buttons.
4. **Mini map in the drawer header** with the place's radius circle. Static or lite, and cheap.
5. **Radius preview in `PlaceRadiusSheet`.** Show the circle live as the slider moves, plus the visit dots that the live counts already compute (`PlaceRadiusPreview.kt`).
6. **Manual visit by map pin.** This is an extra way into `EditTarget.NewVisit`, alongside the place picker: drop a pin, then pick a nearby known place or create one.
7. **Offline regions** under Settings → Maps: download an area, see the cache size, clear the cache. **Check OpenFreeMap's terms on bulk or offline downloads first.** The build plan's fallback is a self-hosted or sideloaded PMTiles extract. Ask the owner before choosing.

## Hard constraints (from AGENTS.md)
- **Privacy (rule 2):**
  - the only network calls allowed are map tiles and the platform Geocoder;
  - no map SDK telemetry: check MapLibre's defaults and turn off anything that phones home;
  - no Google Maps;
  - raw pings never leave the device.
- **No schema change is expected.** If one turns out to be needed, it goes through `migration-guard` with a migration and a `MigrationTestHelper` test (rule 4).
- **Plan first** (rule 8): this touches more than 3 files and adds a dependency. Use the `plan-feature` skill in plan mode.
- **UI and accessibility:**
  - the map is not the only way to do anything; the timeline and the drawer stay fully usable without it;
  - pins and controls are at least 48 dp;
  - the map needs a content description;
  - TalkBack users can reach every visit through the timeline;
  - it has to work at font scale 2.
- **Real traces never enter the repo** (it's public). Synthetic fixtures only. Use `tmp/` and the opt-in replay harness (`-Ptarry.replay=<dir>`) for real days.

## Things to know before planning
- **Raw pings may have been purged** (retention setting, which isn't wired yet). The map has to cope with "no route for this range": draw pins from place centroids and say why the line is missing.
- **Place has `centroidLat/Lng` and `radiusM`.** Visits have no coordinates. A visit's position is its place's centroid, or the mean of its pings, as `PlaceRadiusPreview` already does.
- **Undo, frozen records and `enqueueNow()` all apply.** A manual visit created from the map goes through `TimelineEditor.createManual` like any other.
- **Battery.** The map only renders in the foreground. Nothing new runs in the background.
- **The timeline canvas is the main screen.** Decide with the owner how the Map view is reached: a segmented control (Timeline | Map) or a map toggle on the day screen. The MVP spec's List | Map | Summary control predates the canvas.

## Questions for the owner (ask in planning; rule 12: don't guess)
1. How should the Map view be reached: a segmented control, a toggle in the day header, or something else?
2. Offline regions: rely on the OpenFreeMap terms, or sideload a PMTiles extract for the work area?
3. Should the drawer mini map show only the visit's place, or its route context too?
4. Should a map-pin manual visit be allowed to create a new place, or snap only to known places?
5. Is a "boat over water" check wanted now that tiles exist? The water polygons would let trip mode tell boats from cars automatically. The owner chose WALK/VEHICLE as good enough for MVP, so this is likely post-MVP.
6. What were the "tiny tweaks" from dogfood day 3?

## Suggested task order (refine in planning)
| Task | What | Who |
|---|---|---|
| T0 | Spike: MapLibre + OpenFreeMap style on the Pixel, light/dark, telemetry off, attribution | implementer |
| T1 | Map building blocks: polyline from pings, pin layer, radius circle, all fed by testable pure functions | implementer + test-writer |
| T2 | Day Map view + entry point + pin → drawer | implementer → ui-reviewer |
| T3 | Scrubber + date-time jump, with a non-drag alternative | implementer + test-writer → ui-reviewer |
| T4 | Drawer mini map + live map in the radius sheet | implementer |
| T5 | Map-pin manual visit | implementer + test-writer |
| T6 | Offline regions + Settings → Maps (after the terms decision) | implementer |
| T7 | Review: reviewer + device pass + a short `security-pass` on network/telemetry | reviewer, ui-reviewer |

## Open follow-ups (not phase 5, but don't lose them)
- **Flaky tests: fix these as a small PR first.** Several tests fail intermittently with dispatcher or uncaught-exception races and pass on rerun. #16 fixed one (`EditDrawerViewModelTest`) by awaiting both flows. Others seen: `TimelineViewModelTest.rolloverMovesSelectionWhenFollowingToday`, `TrackingSheetTest.idleOffersStartOptions`, `SegmentationSchedulerTest.enqueueNowChains…`. These can fail CI.
- **Driving detection.** After a drive, check the Sessions screen's activity-recognition log to see whether IN_VEHICLE fires. It never did on day 2. Trip mode no longer depends on it, but it's still diagnostic.
- **Known misses (accepted):**
  - a lunch walk on network-only fixes isn't detected;
  - a brief mooring stop during a boat leg is missed;
  - two sites about 100 m apart with no saved place merge into one (shrinking the radius splits them).
- **Timeline dense case.** A trip between very short visits shows icon-only, and the icon can spill over the neighbours. The owner hasn't decided between that and clipping.
- **Battery optimisation pass,** once there are measured days: capture can sit in MOVING at noisy sites, which only changes the interval now.
- **Phase 3 leftover:** a visible focus ring on trips and gaps.
- **Housekeeping:** remove the `../Tarry-phase3` worktree and `.claude/worktrees/agent-*`.

## How to start the next session
1. Run `git switch main && git pull`, then `git switch -c feat/phase-5-map`.
2. Enter plan mode and run the `plan-feature` skill on this file. Ask the questions above.
3. Write `docs/specs/phase-5.md` from the approved plan, then dispatch the subagents (`.claude/agents/`) task by task. Review before each PR, and do a device pass with the phone unlocked: an adb install works while it's locked, but UI checks need it unlocked.
