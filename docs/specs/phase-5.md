# Phase 5: Map

Branch: `feat/phase-5-map`. Size: L. Parent: `docs/specs/build-plan.md` (5). Product source: `docs/specs/tarry-mvp.md` § Map view, § Edit drawer (header mini map), § Settings → Places (radius preview). Handoff: `docs/handoffs/phase-5-map.md`. Approved 2026-10-24.

## Context
Phases 0–4 and the dogfood fixes are merged, and the DB is Room v1. Phase 5 adds a map so the owner can check a day's record at a glance:
- a Map view of the selected day;
- a mini map in the drawer header;
- a live map in the Places radius sheet;
- a map pin as a way to place a manual visit.

The manual-visit create form was never built, so it is built here first. Recon found that NEW_VISIT renders only a header, and nothing in the app calls `TimelineEditor.createManual`.

## Owner decisions (2026-10-24)
- **Entry:** a compact **Day | Map** toggle, first in the review row (before "Needs review" and "New visit"). It keeps the selected day.
  - Revised 2026-10-25: "New visit" becomes a `+` icon when the row doesn't fit; "Needs review" is hidden on the map.
  - The map runs full width, down to the bottom bar.
  - The owner chose "Timeline | Map". The label is "Day" because the bottom bar already has a "Timeline" tab, and two tabs with the same name confuse TalkBack and the tests.
- **Offline regions are deferred,** along with Settings → Maps. MapLibre's ambient tile cache is all for now.
  - OpenFreeMap allows at most 3,000 tiles or 50 MB per offline download; larger downloads need a PMTiles extract.
- **Drawer mini map:** the place and its radius only.
- **Map-pin manual visit:** it may create a new place.
- **Deferred:** boat-over-water detection (post-MVP).
- **Deferred (2026-10-25): map matching** (snapping trip lines to streets, e.g. a hidden Markov model matcher like OSRM match or Valhalla Meili) goes post-MVP. It needs an on-device road graph (privacy rule 2), so it sits with offline regions. Heavy smoothing is not wanted. Known symptom: on 24 Oct some stretches of the trip line stray off the roads when zoomed in.
- **Day-3 tweaks:** none reported yet.

## Device-pass revisions (2026-10-25, owner)
- **Routes come from trips only.** Each trip runs from the previous visit's place, through its pings, to the next visit's place. A visit is one circle at its place radius in the job colour. Drawing every ping turned stationary network jitter into tangles of lines.
- **Unrecorded stretches are left out** (revised 2026-10-25). A stretch inside a trip with no fixes for longer than `MapConfig.tripBreakMs`, or with a recorded gap, is a break in the line, with a small hollow dot at the fix either side. The real path is unknown and there is no routing service, so no straight line is drawn. Other trips are faded solid lines.
- **Emphasis follows the scrubber.** The trip or visit under the scrubber is solid and full strength. Other trips and visit areas are faded. During a visit the marker sits at the place's centre.
- **Attribution sits top right** of every map, clear of the scrubber; the compass sits below it. The drawer mini map uses the credit MapLibre prints into the snapshot.
- **Only fixes within 50 m make trip lines** (`MapConfig.routeMaxAccuracyM`). Network fixes (50–100 m) jumped hundreds of metres and landed offshore on 23 Oct; on a GPS day the cap drops about 4% of trip fixes. A spike filter was tested on 24 Oct and rejected, because it only removed accurate GPS turns.
- **The scrubber is an overlay.** The map runs down to the bottom bar. The scrubber floats over it as a slim card: one line with coloured visit dots, smaller arrows (48 dp targets kept) and a smaller time pill.
- **Follow-up, a capture bug outside phase 5:** on 24 Oct, 11:17–11:28, tracking recorded no fixes while the owner drove about 2.8 km. Fixes before and after were GPS-grade (8–30 m), and no Gap was recorded. That breaks AGENTS.md rule 6, so it needs `debug-triage` on the tracking side. The 23 Oct hole (09:36–09:48) was a boat trip on a network-fix-only day, so it is put down to the old low-accuracy tracking; re-check on the next water trip. The map shows both as breaks in the line.

## Device pass (2026-10-25)
Pixel 10 for the map, pin taps and the drawer; emulator (Medium Phone API 35, with a local copy of the dogfood DB) for the rest.
- **Light and dark:** day map, pins, visit areas, scrubber and the drawer mini map all render in both.
- **Font scale 2:** the header wraps, the map gets shorter, and the day still fits between the credit and the scrubber card. The credit stops growing at 1.3x so it stays one line.
- **TalkBack:** every control is labelled and at least 48 dp. The map now announces the day summary (it was announcing MapLibre's default description).
- **Network:** while the map loaded, panned and opened a drawer, every TCP connection from the app's UID went to 172.67.74.56:443, an address of `tiles.openfreemap.org`. The release classpath has no analytics, crash or telemetry modules.
- **Fixed during the pass:** the drawer mini map crash, straight lines over unrecorded stretches, network fixes drawn as trips, the lost whole-day fit, the credit at large font sizes, and the TalkBack description.
- **Known:** the credit MapLibre prints into the mini map image is faint in dark mode.
- **Scrubber drag while tracking** (the S1 regression check): the owner confirmed it works on the Pixel.
- **Dogfood, 2026-10-25 (a few hours, manual session):** two short drives, both fully drawn from fixes within 50 m. The service was killed once (12:56:59); a `SERVICE_KILLED` gap was recorded, so the map shows a break. A full work week of dogfooding starts on 2026-10-26.

## Design
- **No schema change.** The DB stays v1. The only data addition is a `LocationPingDao.observeInRange` Flow query.
- **Code layout:**
  - Pure map logic goes in `app/.../map/`, with no Android imports, and is JVM tested. This covers the style builder, route builder, circle polygon, pins and scrubber model.
  - Compose and MapLibre glue goes in `app/.../ui/map/`.
- **Map library:** MapLibre Native Android `org.maplibre.gl:android-sdk:13.7.1`, in an `AndroidView(MapView)` wrapper (`TarryMap`).
  - The wrapper forwards the lifecycle and is destroyed when it leaves composition, so it only renders in the foreground.
  - There is no third-party Compose map library.
- **Style:** `assets/map/style.json` is derived from OpenFreeMap Positron.
  - Every colour in it is a `{{token}}` placeholder, filled by `MapStyle.build` from `TarryColors`.
  - The two map-only tokens, `mapWater` and `mapGreen`, are in `docs/design/tokens.md`.
  - Tiles, glyphs and sprites come from `tiles.openfreemap.org`, which is the only host. We host the modified style ourselves (bundled), as OpenFreeMap requires.
- **Network:**
  - `MapLibreInit` sets an OkHttp client with a unique `Tarry/<version>` User-Agent and at most 4 parallel requests, as OpenFreeMap asks.
  - MapLibre Native has no telemetry. Its POM pulls in no analytics.
  - Attribution is drawn in Compose ("© OpenFreeMap · OpenMapTiles · OpenStreetMap", at least 48 dp). Tapping it opens linked credits.
- **Route:**
  - Drawn from the day's pings, broken at `Gap` ranges and at time jumps over a `MapConfig` threshold.
  - Simplified with Douglas-Peucker before rendering.
  - With no pings (purged or never recorded), pins come from place centroids and a banner explains why the line is missing.
- **Pins:**
  - Numbered in time order.
  - Coloured with the job's `JobPalette` container colour.
  - Hit-tested over at least 48 dp, and a tap opens `EditTarget.Visit`.
  - A visit's position is its place centroid.
- **State:** `TimelineViewModel` exposes `dayMap`, which is collected only while the Map tab is shown.

## Tasks
| # | Task | Size | Exec | Status |
|---|---|---|---|---|
| P0 | Flaky tests | S | claude | Done (#21) |
| T0 | MapLibre + style + attribution + UA/request cap + Day/Map control, then a device check of network hosts | M | claude | Done; device network check moved to T8 |
| T1 | Pure building blocks: `RouteBuilder`, `CirclePolygon`, `VisitPins`, `MapConfig` | M | implementer + test-writer | Done |
| T2 | Day Map view: route, pins, missing-route banner, pin → drawer, fit camera, day summary content description | L | claude → ui-reviewer | Done (device pass pending) |
| T3 | Scrubber (`ScrubberModel`, strip, snap, step buttons, semantics) + date-time jump | L | claude + test-writer → ui-reviewer | Done (device pass pending) |
| T4 | Manual-visit form in NEW_VISIT: times, place search, Save → `createManual`, inline errors, undo | M | implementer + test-writer | Done |
| T5 | Map-pin placement: centre-pin picker, nearby places, `createManualAtNewPlace` with a single undo | M | claude + test-writer | Done (device pass pending) |
| T6 | Drawer mini map (`MapSnapshotter`, cached) | S | implementer | Done (device pass pending) |
| T7 | Radius sheet live map (circle follows the slider, visit dots) | S | implementer | Done (device pass pending) |
| T8 | Review, security pass (network/telemetry), device pass | M | claude | Done 2026-10-25: device pass (Pixel + emulator, see above) and review |

## Acceptance criteria
- **Map view:**
  - shows the selected day;
  - the route breaks at gaps;
  - pins are numbered in time order and coloured by job;
  - a pin tap opens that visit's drawer;
  - a day with no pings shows pins plus a "route not available" banner.
- **Scrubber:**
  - blocks are drawn in proportion to time;
  - dragging moves the marker and centres the map;
  - releasing snaps to a visit start;
  - step buttons reach every visit without dragging;
  - the date-time jump moves to the picked date and time.
- **Manual visit:**
  - can be created from place search, from a known place near a pin, or from a new place at a pin;
  - an overlap shows an error;
  - undo removes the visit, and the place too when it was created with it;
  - the visit is frozen, so segmentation never touches it.
- **Mini map and radius preview:**
  - the header shows the place's circle;
  - the sheet's circle follows the slider live;
  - the sheet's dots match the counts.
- **Privacy:**
  - the only network traffic goes to `tiles.openfreemap.org`;
  - there are no telemetry modules;
  - attribution is visible.
- **Schema:** DB v1, with no new schema JSON.
- **Accessibility:**
  - the map has a content description;
  - controls are at least 48 dp;
  - everything works at font scale 2;
  - the timeline still offers everything the map does.
- **Commands:**
  - `gradlew.bat testDebugUnitTest`
  - `gradlew.bat lintDebug`
  - `gradlew.bat connectedDebugAndroidTest`
  - `gradlew.bat installDebug`, then a device pass with the phone unlocked.

## Risks
- **Ping volume:** a full high-accuracy day is tens of thousands of points, so simplification is mandatory. Measure it in T2.
- **MapView lifecycle in Compose:** one MapView per screen, and it is destroyed on dispose.
- **Debug APK size:** about 90 MB, because MapLibre ships native libraries for every ABI. Consider ABI splits for release later.
- **Renderer:** the build uses `android-sdk-opengl`. The default `android-sdk` artifact is Vulkan-only and declares `android.hardware.vulkan.version` as a required feature, which would rule out older devices and some emulator images.
  - MapLibre's manifest also adds `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` (connectivity checks). Both are normal permissions and expose no location.
  - Release builds turn MapLibre's native logging off, because tile URLs reveal roughly where the user looked.
- **`MapSnapshotter` cost:** cache it. If it's slow, fall back to a non-interactive MapView.
