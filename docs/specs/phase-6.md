# Phase 6: Schedule grid + manual controls UI

Branch: `feat/phase-6-schedule`. Size: M. Parent: `docs/specs/build-plan.md` (6). Product source: `docs/specs/tarry-mvp.md` § Schedule, § Schedule engine, § Manual controls, § Bottom bar and tracking toggle, § Settings → Schedule. Handoff: `docs/handoffs/phase-6-schedule.md`. Approved 2026-10-25.

## Context
The schedule is still the seeded Mon–Fri 07:00–17:00 template (`ScheduleInputsProvider.seedTemplateIfMissing`); nothing else writes or observes it. Phase 6 lets the owner paint the weekly schedule once ("set and forget"), override single weeks, and gives the toggle a long-press sheet. Handoff: `docs/handoffs/phase-6-schedule.md`. Branch `feat/phase-6-schedule` from `main` (worktree). **No Room change** — `schedule_template` / `schedule_override` already exist in v1.

## Owner decisions (2026-10-25)
- **Painting out the current window → stop now** via the normal window-end path (finishing-up grace, SCHEDULE_END, segmentation). No new state.
- **Explicit Save / Discard.** One re-arm + reconcile per save; back with unsaved edits asks to discard.
- **Long-press = custom duration sheet.** Tap keeps today's per-state sheet. Long-press: IDLE → "Start for…", TRACKING → "Pause for…", PAUSED → "Pause until…" (replaces the pause), ATTENTION → same as tap. Length in 15-min steps (15 min–12 h) or "Until a time" (time picker; a time ≤ now means tomorrow, capped at 24 h).
- **No Gap for the past:** an edit counts from the moment it's saved. Painting 07:00–17:00 at 10:00 starts tracking now; 07:00–10:00 is not "missed".
- **Gesture:** tap toggles a cell; long-press then drag paints/erases (first cell decides, haptic on start); plain swipe scrolls; auto-scroll near the grid's top/bottom edge.

## Design

### Pure logic (JVM-tested)
- `core-data/.../model/Schedule.kt` — add `withSlots(day, slots: IntRange, active)`, `copyDay(from, to: Set<DayOfWeek>)`, `clearDay(day)`, `daySlots(day): List<Boolean>`. Reused by onboarding (phase 8).
- `app/.../ui/schedule/PaintStroke.kt` — stroke state: first cell sets mode (paint if it was off, erase if on); cells passed along the drag (interpolated between pointer samples within one day column and across columns) get that mode; returns the new `Schedule`.
- `app/.../ui/schedule/ScheduleSummary.kt` — subtitle text for the Settings row: "Mon–Fri 07:00–17:00", "Off", or "Custom" (days sharing one identical contiguous range are grouped).
- `app/.../tracking/ScheduleEdit.kt` — the "counts from save" marker: `ScheduleEdit(atMs, weekStart, before: Schedule)` = the effective bitmap of the week containing `atMs` as it really was before the save. `ScheduleEdit.record(prev, effectiveBefore, weekStart, at)` merges with a previous edit in the same week (slots ending ≤ `prev.atMs` keep `prev.before`), so repeated saves don't lose earlier protection. Stored in `TrackingStatusStore` (DataStore, local control state; add `scheduleEdit` field + setter).
- `EffectiveScheduleCalculator` — `ScheduleInputs` gets `edit: ScheduleEdit?`. In `buildWindows`, for dates in `edit.weekStart`'s week, slot time before `edit.atMs` comes from `edit.before`; a slot spanning `atMs` that was off before starts at `atMs`. Live state/next transition are unaffected (they're ≥ now). `ScheduleInputsProvider.inputs` passes it through.
  - **DST:** keep the existing `ZonedDateTime` slot semantics. Add calculator tests for Sydney spring-forward (2026-10-04, 02:00–03:00 doesn't exist) and fall-back (2026-04-05, 02:00–03:00 twice) with painted 02:00/02:30 slots: no crash, windows non-overlapping and sane. Grid shows wall-clock slots; no special UI.

### Saving re-arms (rule 5, rule 6)
- `TrackingController.updateSchedule(change: ScheduleChange)` under the mutex, `ScheduleChange` = `SaveTemplate(schedule)` | `SaveWeek(weekStart, schedule)` | `ClearWeek(weekStart)`:
  1. compute the current week's effective bitmap before;
  2. write via `ScheduleRepository` (inject it into the controller);
  3. if the current week's effective bitmap changed, record `ScheduleEdit`;
  4. **re-bound a Stop:** an active PAUSE with end mode UNTIL_STOPPED and `end > now` (how `stop()` encodes "until next window") gets `end` recomputed against the new schedule (next window start, or look-ahead bound) — otherwise a window painted later today would stay suppressed;
  5. `reconcileLocked(ReconcileSource.UI)` → starts/stops capture and re-arms the single alarm.
- PAUSE NEXT_WINDOW resolves dynamically already — no change. Override for the current week works through the same path (it is the effective schedule).
- `ScheduleViewModel` calls it on `captureScope` (like `ShellViewModel`), then `TrackingStateRepository.refresh()`.

### Schedule screen (`app/.../ui/schedule/`)
- `ScheduleRoute` in `Routes.kt`; `entry<ScheduleRoute>` in `TarryApp.kt`; Settings → Tracking section gets a first row "Schedule" with `ScheduleSummary` of **this week's effective** schedule as subtitle ("This week has its own schedule" when an override exists).
- `ScheduleViewModel` (Hilt): mode (Every week | This week only), viewed week (current week and forward only, ‹ › nav, date-range label), saved vs draft `Schedule`, dirty flag, `hasOverride`. Loads template / override from `ScheduleRepository`; in week mode without an override the draft starts from the template.
- `ScheduleScreen` / stateless `ScheduleEditorContent(state, actions)`:
  - top: segmented Every week | This week only; week nav in week mode; banner "This week uses its own schedule · Use every-week schedule" (= `ClearWeek`, with confirm) when the viewed week has an override; in template mode a note when the current week has one;
  - presets row: "Mon–Fri 07:00–17:00", "Clear";
  - `ScheduleGrid` (stateless, reusable by onboarding): sticky day header (Mon–Sun, today highlighted in week mode), `LazyColumn` of 48 rows × 48 dp, initial scroll to 06:00, hour labels; tap = toggle; long-press-drag = `PaintStroke`; edge auto-scroll;
  - day header tap → day sheet: **Set hours** (start/end time pickers, 30-min steps, or Off), **Copy to…** (day checkboxes + "Weekdays"), **Clear day**. Set hours is the WCAG 2.5.7 non-drag alternative;
  - bottom: Save / Discard when dirty; `BackHandler` with discard confirm.
- **Accessibility:** each cell `toggleable` with content description "Monday 07:00 to 07:30, on/off"; row semantics merged per cell, headers are buttons with "Edit Monday"; contrast via tokens (`docs/design/tokens.md`, active cell = accent, inactive = inset); font scale 2 checked. Known deviation to confirm in ui-review: cells are ~42–44 dp wide on a 360 dp screen (7 columns can't reach 48 dp); the 48 dp day-header → Set hours path is the equivalent control.

### Toggle long-press + manual controls
- `TrackingToggle`: add `onLongClick` param; `TarryBottomBar` / `TarryApp` wire it to a new `CustomDurationSheet` (`ui/shell/CustomDurationSheet.kt`, stateless content + sheet chrome, same pill rows as `TrackingSheet`). ATTENTION long-press opens the normal sheet.
- `TrackingController.pause(durationMin: Int)` overload (PAUSE, DURATION, `now + min`); start uses existing `start(DURATION, min)`. Limits (`customDurationStepMin=15`, `customDurationMaxMin=720`) in `TrackingConfig`.
- MVP § Manual controls check: start until stopped / end of day / duration ✔ (fixed 1/2/4 h + custom fills "chosen duration"); pause 30 min / 1 h / next window ✔; stop ✔. No rebuild.

## Tasks
| # | Task | Agent |
|---|---|---|
| T0 | Branch + worktree; copy this plan to `docs/specs/phase-6.md`; build-plan status line | main |
| T1 | `Schedule` helpers, `PaintStroke`, `ScheduleSummary` + JVM tests | implementer → test-writer |
| T2 | `ScheduleEdit` + calculator clipping + DST tests; status-store field | implementer → test-writer |
| T3 | `TrackingController.updateSchedule` + stop re-bound + `pause(durationMin)`; controller tests | implementer → test-writer |
| T4 | Schedule screen, VM, grid, day sheet, route, Settings row; VM + Compose tests | implementer → test-writer → ui-reviewer |
| T5 | Long-press `CustomDurationSheet` + wiring; Compose tests | implementer → test-writer → ui-reviewer |
| T6 | Review (`reviewer`), device pass (`deployer`), PR | reviewer, deployer |

Follow-ups from the handoff (phase 5 nits, map items, the 24 Oct no-Gap drive bug for `debug-triage` on its own `fix/` branch) stay listed, not fixed here.

## Tests (rule 7)
- core-data `ScheduleTest`: `withSlots`, `copyDay`, `clearDay`, `daySlots`.
- `PaintStrokeTest`: first-cell mode, erase, interpolation, cross-column.
- `ScheduleEditTest` + `EffectiveScheduleCalculatorTest`: added slots before `atMs` aren't active in `activeIntervals`; removed slots before `atMs` still are; repeated saves; other weeks untouched; DST cases.
- `TrackingControllerTest` (existing fakes in `TrackingFakes*.kt`): save re-arms the alarm at the new next transition; painting out the current window → grace then SCHEDULE_END; painting in a started window → service starts, no Gap for the earlier part; current-week override; stop then paint a later window today → tracking resumes at it; custom pause.
- Robolectric Compose: `ScheduleGridTest` (tap toggles, long-press-drag paints, first cell erases, semantics/content descriptions), `ScheduleEditorContentTest` (Save/Discard, mode switch, drop override), `CustomDurationSheetTest`, `TrackingToggle` long-press callback; `ScheduleViewModelTest`.

## Verification
- `gradlew.bat assembleDebug testDebugUnitTest lintDebug` green; CI green.
- Device (Pixel, `installDebug`): paint a window starting in 2 min → service starts at the edge; erase the current window → "Finishing up…" then idle; add a window already started → starts now, timeline shows no Gap before; this-week override then "Use every-week schedule"; long-press start for 45 min / pause until a time; TalkBack walk through cells and the day sheet; light/dark, font scale 2.
- `adb shell dumpsys alarm | grep me.tarry` shows one alarm at the expected next transition after each save.
