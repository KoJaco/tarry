# Handoff: phase 8a.3 — minimise the map overlay; attribution behind an info button

**From:** Claude (planner, owner's request 2026-11-11) → **To:** implementer · **Branch:** `feat/phase-8a3-map-polish` (PR #35, never main).
Read first: `AGENTS.md`, `docs/design/ui-rules.md` (§4 motion), `docs/design/tokens.md` (§ Motion). Builds on
`phase-8a3-map-strip-and-time-text.md` and `phase-8a3-review-fixes.md`.

## Owner decisions (build exactly this)
### A. Minimise / maximise the Map tab overlay (`ui/timeline/DayScrubber.kt`)
- Two states: **expanded** (today's card + time pill + strip) and **minimised** (time pill + strip only, no card).
- **Expanded:** a minimise button (chevron down icon) sits immediately **left of the card's round Open ›** button, same
  size and style (48 dp, `inset` fill). For a gap card (no Open) it sits where Open would be.
- **Minimised:** the card collapses away; the strip row (prev ‹, strip, next ›) **moves left** to make room, and a maximise
  button (chevron up, same style, 48 dp) **animates in on the right of the strip row**. Expanding reverses it.
- **Swipe:** a vertical swipe down on the overlay minimises, up maximises. The strip's horizontal scrub must keep working
  exactly as now (only vertical-dominant drags toggle; use a slop/threshold so a scrub never toggles). Swiping over the
  card area must work; over the strip it may, if it doesn't conflict with scrubbing.
- **Motion:** 200 ms, M3 emphasized easing (tokens § Motion), animating the card's size/visibility, the strip row's
  width/offset and the maximise button (fade + small slide/scale). Reduced motion (`rememberReducedMotion()`): instant.
- **Remembered across launches:** a new boolean in `SettingsRepository` (DataStore, e.g. `mapOverlayMinimised`, default
  false), read/written through the existing ViewModel path the timeline/map screen already uses. No Room change.
- **Map:** the overlay's measured height already drives the bottom inset; make sure toggling updates the camera padding
  (centring) without re-fitting the day or jumping the camera beyond the padding change.
- **Accessibility:** content descriptions "Hide details" (minimise) / "Show details" (maximise), state exposed (e.g.
  `stateDescription` "Details shown"/"Details hidden"), both 48 dp, focus order sensible after a toggle. The swipe has the
  buttons as its accessible alternative. The card's polite announcements stay snap-only and don't fire while minimised.
- Strings in `res/values/strings_map.xml`.

### B. Map attribution behind an info button (`ui/map/TarryMap.kt` and wherever the attribution line is drawn)
- The full attribution line ("© OpenFreeMap · OpenMapTiles · OpenStreetMap") **shows when the map first appears in a
  process session**, then **collapses after ~5 s** to a small round ⓘ button (48 dp target, minimal visual, top-right where
  the line sits today). Tapping ⓘ shows the full line again (and it collapses again after ~5 s, or on a second tap).
  This keeps the OpenStreetMap attribution guideline for collapsible attribution on small screens; don't make it ⓘ-only.
- Links in the full line, if any today, keep working. Content description for ⓘ: "Map data credits".
- Animation per tokens § Motion; reduced motion: instant.
- Since the line no longer permanently covers the map top, re-check `MapAttributionClearance` (the top inset used for the
  day fit and centring): it should clear the ⓘ button, not the full line.

## Tests (rule 7)
- Scrubber: minimise button left of Open; toggling hides the card and shows the maximise button right of the strip; strip
  still scrubs after a toggle; a vertical swipe down/up toggles, a horizontal drag never toggles; reduced motion is instant;
  48 dp targets and descriptions/state; overlay height shrinks when minimised (so the inset updates).
- Settings: the flag round-trips and defaults to false; the screen starts in the stored state.
- Attribution: full line shown first, collapsed to ⓘ after the timeout (use the test clock), ⓘ tap re-shows it.
- Light/dark, font 1.0/2.0 where layout matters.

## Rules
No Room schema change, no segmentation change; `ScrubberModel.kt` untouched. Small diffs. `./gradlew.bat testDebugUnitTest
:segmentation:test lintDebug assembleDebug` green. Commits by part (A, B), explicit paths (include this handoff in the first),
never `.idea/` or `.claude/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not
push. No adb, no device. Report files, tests, decisions, skips.
