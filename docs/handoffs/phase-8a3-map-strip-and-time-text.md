# Handoff: phase 8a.3 — Map tab day strip, sized stops; compact timeline time text

**From:** Claude (planner, from the owner's spec of 2026-11-11) → **To:** implementer · **Branch:** `feat/phase-8a3-map-polish` from main **after 8a.2 merges** (never main). Do this before the marketing screenshots (`marketing-screenshots.md`), since both screens appear in them.

**Mockup:** the owner's Tarry design canvas, artboards "Map tab · day strip and sized stops" and "Map tab · spec sheet" (link with the owner; it holds real place names, so it is **not** copied into this public repo). Layout details are transcribed below with neutral names.

Read first: `AGENTS.md`, `docs/design/ui-rules.md`, `docs/design/tokens.md`.

## Part A — Map tab

### Goal
Make the Map tab answer "where did my time go?" at a glance. Today the scrubber is a thin line with dots and every pin is the same size, so a 5-minute stop and a 2.5-hour job look identical. Three changes: pins sized by time spent, a taller day strip that reads like the timeline turned sideways, and a card for the item under the thumb. Scrubbing, snapping, stepping and the marker already work and must not change.

### Constraints
- Colours only from `TarryTheme.colors` and `JobPalette` (never literals), light and dark.
- Visual language matches the timeline columns: job colour = the job's container/border/text; no job or needs review = card fill with an outline border, dashed where the drawing API allows it.
- Touch targets stay 48 dp (`pinTouchDp`, `MinTouch`).
- `ScrubberModel` behaviour (fractions, snap, next, previous, positionAt, itemAt) is unchanged; only the drawing and the overlay change.
- Out of scope: travel-mode line styles, merging revisits into one "2×" pin, moving the map credit, any change to the Timeline tab (Part B is separate and text-only).

### Files to touch (and to leave alone)
- `map/VisitPins.kt`: dwell-based radius on `PinGroup`; numbers label kept.
- `ui/map/DayMapView.kt`: `setPins` uses the new radius, sorts features, hides small labels, outlines the active pin; the fit margin grows for the taller overlay.
- `ui/timeline/DayScrubber.kt`: block-style strip, hour ticks, selected item card; remove the visit-start dots.
- `res/values/strings.xml`: card and accessibility strings.
- Tests in `map/` (pure) and the existing scrubber/map UI tests.
- **Do not touch:** `ScrubberModel.kt`, `RouteBuilder.kt`, `DayMapModel.kt` route logic, `TimelineContent.kt`, `JobBlocks.kt`.

### 1. Pins sized by time spent (`map/VisitPins.kt`, `ui/map/DayMapView.kt`)
- Pure function in `map/`: `fun dwellRadius(durationMs: Long): Float = MinR + (MaxR - MinR) * sqrt(min(durationMs, CapMs) / CapMs.toFloat())` with `MinR = 7f`, `MaxR = 28f`, `CapMs = 4 h`. The square root makes circle area grow with time, so a 2 h stop doesn't dwarf everything. Reference values: 5 min 10.0 · 15 min 12.3 · 30 min 14.4 · 1 h 17.5 · 2.5 h 23.6 · 4 h+ 28.
- `PinGroup.radius` becomes `max(dwellRadius(sum of the group's visit durations), labelRadius)`, where `labelRadius` is today's formula (`PinRadius` widened per character). A revisited place is one circle sized by its total time ("1, 5"); a long label still fits.
- In `setPins`:
  - Emit features sorted by radius, largest first, and set `circleSortKey`/`symbolSortKey` to `-r`, so short stops sit on top of long ones and stay tappable.
  - Hide the number when the circle is too small for it: `textOpacity = step(get("r"), 0, 13, 1)`. Numbers still appear in the strip card and drawer.
  - `addBooleanProperty("active", …)` (the pin group containing `activeVisit`); the stroke becomes ink, 3 dp, when active; otherwise the job border/outline, 2 dp as now.
- Tap hit-testing already uses `pinEdgeDistancePx` with the per-feature `r`, so larger circles work unchanged. Check overlapping pins pick the smaller, top-most one (test).

### 2. Day strip (`ui/timeline/DayScrubber.kt`)
Keep the layout: previous button, strip, next button, time pill floating above the thumb.
- Strip height goes from one 48 dp row to a 48 dp drag row (the bar) plus 16 dp of hour labels underneath. The whole 64 dp area is draggable.
- Draw order inside the Canvas, centred on the bar's y:
  1. Base line across the worked span: faint, 3 dp (as now).
  2. Trips: muted, 3 dp, round caps (as now).
  3. Gaps: alert, dashed (as now).
  4. Visits: rounded rectangles, 14 dp tall, corner radius 4 dp, width at least 4 dp so a 2-minute stop still shows. With a job: fill container, stroke border 1.5 dp. No job or needs review: fill card, stroke outline 1.5 dp dashed (`PathEffect.dashPathEffect(4dp, 3dp)`).
  5. Keep the existing emphasis: the item under the thumb at full strength, the rest at `FadedAlpha` (mockup: about 40 %).
  6. Remove the visit-start dots: block edges now mark where steps and snaps land.
  7. Thumb unchanged (ink, card ring), drawn last.
- Hour ticks: for each whole hour inside `model.range`, a 5 dp outline tick under the bar and a `bodySmall` muted label centred on it ("8", "9" … "12", "1", or "08"…"13" in 24-hour). Drop every second label if labels would sit closer than 24 dp, and at font scale ≥ 1.5.
- The strip's content description becomes "Day from 7:30 am to 3:21 pm, 7 visits, 6 trips. At 9:40 am: Harbourside Apartments." Update it on snap, not on every drag frame.

### 3. Selected item card (`DayScrubber.kt` overlay)
A row above the strip, inside the same overlay card, showing `model.itemAt(current)`:
- **Visit:** place name (`titleMedium`, 1 line, ellipsis); "8:31 – 11:06 · 2 h 35 m" (`bodyMedium` muted); then either the job chip (job colours, "● Job · Billable" when billable) or a "Needs review" chip (`accentSoft`/`onAccentSoft`) plus an "Add job" text button.
- **Trip:** "Drive · 6.0 km · 16 min" (walk/drive icon as in the timeline trip row) and "Near <from> → <to>" (`bodySmall` muted).
- **Gap:** "No location · 12 min" in alert text, with the cause line under it in muted text (mockup: "Tracking paused or signal lost"; use the existing gap cause labels, not a new generic line).
- Mockup detail: the card row ends with a round "Open" chevron button (`inset` fill); make it 48 dp (the mockup's 44 is below the floor). The whole card is also tappable.
- Tapping the card (or Open) opens the same drawer a pin tap opens. Add job opens the existing job picker for that visit.
- Height is fixed at the two-line size (a card that changes height while dragging makes the strip jump). Announce changes politely on snap only.
- Increase the day-fit bottom padding (the margin constant near the top of `DayMapView.kt`) by the overlay's new height, so fitted pins never sit under the card.

### Tests
- `dwellRadius` reference values; `PinGroup.radius` (dwell vs label); feature sort order and `active` property; overlapping pins pick the smaller one.
- Scrubber: strip height 64 dp and fully draggable; no visit-start dots; minimum block width; hour labels thin out at 1.5×; content description updates on snap only; card shows visit / trip / gap variants; card height constant while dragging; card tap and Add job callbacks; 48 dp targets. Light and dark, font 1.0 and 2.0.
- Existing scrubber/map tests stay green; `ScrubberModel` tests untouched.

## Part B — compact timeline time text (owner decisions 11 Nov: cut-off 10 min; gap wording "1m gap")
Problem: very short items carry a full range ("4:13 pm–4:14 pm") that says less than a duration would.

Proposed rules (one shared helper next to `clampedTimes` in `ui/timeline/TimelineText.kt`, used by the visit, gap and cluster texts on the Timeline only):
1. **Drop the repeated am/pm** inside a range when both ends share it: "4:13–4:41 pm", "11:50 am–12:10 pm" stays. 24-hour unchanged.
2. **Short items show one time plus a duration** instead of a range, below `TimelineText.ShortItemMs` = **10 min** (strictly under): a visit "4:13 pm · 6m"; a gap **"1m gap"** (compact unit, no space, as the owner prefers; under a minute "<1m gap"). The time stays in the spoken description and the gap sheet. Use plural-safe string resources for the compact forms; other duration texts in the app keep their current format.
3. Markers already use single times ("Start 7:30 am") — unchanged.
4. Spoken descriptions keep full ranges for screen readers.
Tests: the helper (12/24 h, same and different half-days, short threshold edges), Timeline rows at font 1.0/2.0.

## Rules
No Room schema change, no segmentation change. Small diffs. `gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by part, explicit paths (include this handoff in the first), never `.idea/` or `.claude/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`. Then `ui-reviewer` on the emulator with demo data (never the owner's data in screenshots that leave `tmp/`). Report files, tests, decisions, skips.
