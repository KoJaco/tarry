# Handoff: phase 8a.3 — code review and emulator UI review fixes

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-11 · **Branch:** `feat/phase-8a3-map-polish` (never main).
Spec: `phase-8a3-map-strip-and-time-text.md`. Read `AGENTS.md`, `docs/design/tokens.md`, `docs/design/ui-rules.md`.

## Must-fix
1. **Midnight-clamped ranges lose am/pm** (`TimelineText.kt` `rangeParts`). A row clamped at `dayEnd` ends at the next day's
   12:00 AM, so "6:00 AM → 12:00 AM (continues)" renders "6:00–12:00 AM". Merge the marker only when both ends fall on the
   same local date (an end exactly at the next midnight counts as the next date). Fix the test that blessed
   "12:00–12:00 AM (from previous day) (continues)" and add one for each case.
2. **Time pill target overlaps** (`DayScrubber.kt`, `PillBand` 32 dp + 48 dp `requiredSizeIn`): the strip wins taps on the
   pill's lower part and the pill covers the card's Add job row. At font 2.0 the emulator shows ~21 px overlap with Add job.
   Give the pill its own 48 dp band so pill, card row and strip never overlap at any font scale. Test: a tap at the pill's
   visual bottom edge fires the pill action, and the pill's bounds don't intersect Add job's at font 2.0.
3. **Card at font 2.0 clips the duration** ("7:30 AM – 9:00 AM · 1 h …"). Use the timeline's compact range
   (`TimeFormats.rangeParts`, incl. "(continues)"/"(from previous day)" like the timeline) for the card's visit time line, and
   at font scale ≥ 1.5 reserve two lines for it. Height stays fixed across item kinds for a given font scale (it may differ
   between scales). Title stays one line with ellipsis.
4. **Map covered at font 2.0; fit ignores the overlay.** The day fit must use the overlay's *measured* height as bottom
   padding and must re-fit once that height is known (never fit with 0 then leave it). Verify by test where possible
   (pure: padding = measured overlay + constant).
5. **Faded strip items nearly invisible in dark.** Keep strokes/outlines of non-selected visit blocks, trips, base line and
   gap at a strength that meets ≥ 3:1 against `card` in light and dark (check tokens.md values; compute, don't guess); fade
   only fills. Note the numbers in your report.
6. **Visit number shown nowhere when the pin hides it.** Prefix the card title with the visit's pin number, e.g. "3 · Office"
   (same number as the pin group label), so the spec's "numbers still appear in the strip card" holds.
7. **Crash guard:** hour-label `coerceIn(0f, size.width - layout.size.width)` → upper bound `maxOf(0f, …)`.
8. **Tests that prove nothing:** `shortVisitReadsTimeAndDurationAndLongVisitsKeepTheRange` must assert the exact text
   ("4:13 PM · 6m") on a block tall enough to render the range line (or the composable directly). Add a visit test at font 2.0
   and a short midnight-clamped item test.

## Should-fix
- **Overlay card is translucent** (map labels show through in dark): make its surface opaque (`card`/`container` at full alpha).
- **Trip card second line** "Near A → B" is ellipsized before "→ B": use `TextOverflow.MiddleEllipsis` if the Compose version
  supports it on a single line, else shorten each side's place text so both ends show. Keep the existing "Vehicle"/"Walk" labels.
- **Per-frame work while dragging:** derive `stripDescription`/`cardDescription`/spoken text from `settled` only; remember
  `PathEffect`/`Stroke` objects instead of allocating per visit per frame; compute `itemAt` once per frame.
- `StripTicks.kt`: format hour labels with a locale `DateTimeFormatter` ("H"/"HH"/"h") instead of `"%02d".format`.
- `DayScrubber.kt`: derive the magic `6.dp` from `BlockHeight/2 + TickLength + TickGap`.
- `DayCanvas.kt`: restore the dropped space in `timeline_gap_row, stringResource` (drive-by).
- Add job: check it isn't announced twice (content description plus inner Text); keep one.

## Leave as is (decided)
Gap card has no Open. Pill clamps at the strip's ends. Marker covering a small active pin. One-line short visit blocks keep
"Title · 6 min" (handoff limits the compact unit to the time-range line and gap rows). Add job uses the unclamped visit range.

## Rules
No Room or segmentation change; `ScrubberModel.kt`, `RouteBuilder.kt` untouched. Small diffs. `./gradlew.bat
testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by item group, explicit paths (include this
handoff in the first), never `.idea/` or `.claude/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
Do not push. No adb, no device. Report files, tests, contrast numbers, decisions, skips.
