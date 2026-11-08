# Handoff: phase 7.7 — Timeline finish (the 7.5 area 1 picks)

**From:** Claude (planner) → **To:** implementer, then test-writer · **Date:** 2026-11-08 · **Branch:** `feat/phase-7.7-ui-finish` (worktree `.claude/worktrees/phase-7.5`, from origin/main 76a3b30).

## Context
These are the last Timeline items from the 7.5 UI pass. The owner picked each option on 8 Nov 2026. Don't restyle beyond what is written here. If something is ambiguous, stop and ask (rule 12). One commit per item, staging explicit paths only (never `.idea`, never `-a`). Every behaviour change ships with a test (unit or Robolectric Compose, under `testDebugUnitTest`). Never run `connectedDebugAndroidTest` while the Pixel is attached.

## Items (owner picks)

### T1. The review chip becomes the filter (`ReviewRow.kt`, `SummaryCard.kt`, `TimelineContent.kt`)
- Remove `NeedsReviewToggle` from the review row. The row keeps Timeline/Map + Add job.
- The "N visits need review" chip in the summary card becomes a toggle:
  - **Off state:** it looks as it does today.
  - **On state:** it shows a leading check icon and a stronger fill (`accent` fill, `onAccent` text).
  - **Semantics:** `Role.Checkbox` or toggleable, with label-in-name and a 48 dp touch target. Keep test tag `timeline_filter_review` on the chip.
  - It only shows when the count is above 0. If the count drops to 0 while the filter is on, the filter turns off (check the current behaviour and keep it equivalent).
- Remove strings that are no longer used. Hidden in map view, as today.

### T2. Tall blocks fill their height (`DayCanvas.kt` visit block, and the job block in `JobColumn.kt` for consistency)
- Title `maxLines` comes from the available height:
  - first reserve the times line, the warning row and the review chip when they are present;
  - then the title gets as many lines as fit (at least 1);
  - leftover height lets the times/warning wrap too.
- Ellipsis only when the content truly doesn't fit. It must react to zoom, which changes the height.
- Measure with the real line heights for the current font scale, not literals. Put any constants in `DayCanvasDefaults`.

### T3. Short visits: handles outside the card (`VisitResize.kt`, `DayCanvas.kt`)
- When the selected visit's height is under the space needed for two non-overlapping handles plus the card text (define the threshold in `DayCanvasDefaults`), place the top handle just above the card's top edge and the bottom handle just below its bottom edge, outside the card. Otherwise keep today's placement.
- The touch areas stay 48 dp wide (`HandleTouchWidth`) and are centred on the visit card.
- They must not overlap each other. Keep the drag and accessibility actions unchanged.

### T4. Hour ticks stronger (`DayCanvas.kt`)
- Hour lines and hour ticks use `outline` (at least 3:1 on card). Minor marks (15/10/5 min) stay `line`.

### T5. Hour labels above their lines at large font (`DayLayout.kt`, `DayCanvas.kt`, `JobColumn.kt`)
- **From `fontScale >= 1.5`:**
  - the side time column collapses to just the rail;
  - each hour label is drawn just above its hour line, starting at the canvas's left padding, on a `card` background so it stays readable over blocks;
  - visits and the job column get the freed width.
- **Tracking markers ("Start 7:30 am" / "Off"):** follow the same rule, placed above their tick on the left.
- **Below 1.5:** unchanged.
- Define the threshold as a constant. The job column's `jobFraction` and its 48 dp minimum are rechecked so job names are readable at 2×.

### T6. Trip connector line (`DayCanvas.kt`)
- Each trip draws a dotted vertical line in `outline` colour, 1.5 dp wide, at the visit column's left inner edge plus the icon centre. It runs from the previous visit's bottom to the next visit's top, covering the trip's time range, behind the trip row.
- The car/walk row stays where it is, vertically centred on the line when the trip is taller than the row.
- It is drawn under blocks, never over text.

### T7. "Not tracking" hatch and a trimmed start (`DayLayout.kt`, `DayCanvas.kt`, `TrackingMarkers.kt`)
- **Off stretches,** from an Off/Stop marker to the next Start (or to now / day end if tracking never restarted, only on past time): fill with a faint diagonal hatch (`line` colour at the existing gap-hatch style) and a small `muted` "Not tracking" label at the top of the stretch, if it fits.
  - This is visual only. Gaps (rule 6) keep their own look. Where a Gap and an off stretch overlap, the Gap wins.
- **Before the first Start of the day,** the canvas starts at the hour containing that Start, not at the window/day start. This only applies when nothing (visit/trip/job/gap) exists earlier. Today and past days only.
- Expose the off stretches through semantics so TalkBack reads "Not tracking, 7 am to 7:30 am".

### T8. Zoom pill uses `inset` (`ZoomControls.kt`)
- Change the background from `card` to `inset`, in both themes. Keep `surfaceEdge` (dark-only edge).

### T9. Fixes (no owner choice needed)
- **Off-marker under the now-pill:** a tracking marker label within the now-pill's vertical extent at 15 min zoom is moved clear (above or below), never hidden.
- **Nav bar labels clipped at 2×:** descenders are cut ("Summarv"). Give the label its full line height. The labels stay capped at 1.3× (decision 3A).
- **Week strip wraps to two rows at 2×:** keep one row. When the seven days don't fit, the strip scrolls horizontally with the selected day kept in view.
- **Suggestion (needs-review) dashed outline:** must reach at least 3:1 on card in light and dark. Use `outline` if it is currently `line`/`faint`.

## Verify
- `gradlew.bat testDebugUnitTest lintDebug`: green, with no new lint warnings.
- Planner checks screenshots on the Pixel (light/dark, font 1.0 and 2.0) after the build.
