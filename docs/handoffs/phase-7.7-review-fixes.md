# Handoff: phase 7.7 — final UI review fixes

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-08 · **Branch:** `feat/phase-7.7-ui-finish`. Owner picks from the final `ui-reviewer` pass (screenshots in `C:\Users\korij\AndroidStudioProjects\Tarry\tmp\ui-review-7.7-final\`). Same rules as the other 7.7 handoffs: one commit per item, explicit paths, a test per behaviour change, no instrumented tests, no push. Follow `docs/design/ui-rules.md`.

## Items
- **R1. Summary range chips at 2×:** the selected chip ("This week") is cut off under the fade.
  - When the selection changes, and on first composition, `animateScrollTo` so the selected chip is fully in view. Snap instead of animating under reduced motion.
  - Put it in `FadingScrollRow` or a small helper if it fits generically. The Timeline review row and the Sessions export row may use it too.
  - Robolectric test at font 2.0: the selected chip's bounds are inside the row's.
- **R2. Unlabelled billed value (Summary By job, and By day rows if they have the same pattern):** at 1×, the right-hand figure has no label, so "1 block · 15 min" next to "30 min" is ambiguous.
  - Add a small `labelSmall` `muted` "Billed" caption above (or before) the right-hand figure in the 1× layout, matching the 2× "Billed <time>" wording.
  - The TalkBack sentence is unchanged unless it doesn't say "billed" (then fix it).
- **R3. Place detail text:**
  - "1 visit" (and other counts there) use a non-breaking space between number and noun, and the line breaks only at " · ".
  - The hint "New visits here are suggested for this job." shows only when a default job is set.
- **R4. Permissions warning:**
  - The amber "Required for scheduled tracking" (`accentStrong`) line shows only when that permission is NOT granted.
  - When it is granted, show "Needed for scheduled tracking" in `muted`, or drop the line. Use the muted line.
  - Replace the straight quotes around "Allow all the time" with curly quotes (‘ ’ or “ ”, matching the rest of strings.xml).
- **R5. "+ Add job" at large font:** from `fontScale >= 1.5` (reuse an existing 1.5 threshold constant if one fits, e.g. `StackedTimeLabelsFontScale`, or add one), "+ Add job" leaves the scrolling row and sits on its own line under the Timeline | Map toggle, fully labelled and left-aligned. It's still hidden wherever it is hidden today (e.g. map view, if so). Below 1.5 nothing changes. Robolectric test at 1.0 and 2.0.
- **R6. Week strip day names at large font:** when the strip is in its scrolling (large font) mode, use two-letter weekday names (Mo Tu We Th Fr Sa Su) instead of single letters.
  - Derive them from the locale if possible, e.g. `TextStyle.SHORT` truncated to 2, or `NARROW` → `SHORT`. Keep it correct for English; don't hard-code a list unless there's no locale API that gives two letters.
  - Content descriptions are unchanged. Unit test.
- **R7. Coincident map markers:**
  - When two or more numbered visit markers on the day map fall at the same spot (within a few px at the current zoom, or the same place id/centre), draw one marker labelled with both numbers ("6, 7"), not overprinting glyphs.
  - Simplest acceptable: group by identical place/centre coordinate before building the symbol layer.
  - Find where markers are built (`ui/map/DayMapView.kt` / route builder). JVM test for the grouping.
- **R8. Date and range format:**
  - The Summary range line uses an en dash with no spaces ("2 Nov 2026–8 Nov 2026"). Keep non-breaking spaces inside each date so it only breaks at the dash; add a zero-width space after the dash like G1.
  - Sessions rows show "6 Nov 2026, 4:14 pm–4:30 pm": the date once and no seconds when start and end are on the same day. When they differ: "6 Nov 2026, 11:50 pm–7 Nov 2026, 12:10 am".
  - Screen text only: do NOT change CSV, PDF, GPX or backup formats.
  - Unit tests for the formatter.
- **R9. Docs:** in `docs/design/ui-rules.md` §2, add that section headers (`SectionHeader`, and the Summary By day date headers) are uppercase `labelSmall`, the one deliberate exception to sentence case, if that is how they're implemented. Check first, and if they're uppercased in code via `.uppercase()`, document it.

## Report
Commits (hash + subject), test count/result, new lint warnings, anything skipped and why, and visual values chosen.
