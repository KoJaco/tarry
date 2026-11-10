# Handoff: phase 8a.2 — empty-day reason

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-10 · **Branch:** given in your prompt (never main).

Read first: `AGENTS.md`, `docs/design/ui-rules.md`, `docs/handoffs/phase-8-launch.md` § 2026-11-10.

## Goal
When a Timeline day has nothing on it, say why, so an empty day never looks like a silent failure (rule 6).

## Build
1. **ViewModel** (`ui/timeline/TimelineViewModel.kt`; internal ctor ~157-195 with lambda inputs, `@Inject` ctor ~197-227):
   - Inject `ScheduleWindowChecker` (`tracking/ScheduleWindowChecker.kt`, `activeIntervals(from, to): List<WindowBounds>`; covers template, week override, manual overrides, test window). Add an internal-ctor lambda `activeIntervals: suspend (Long, Long) -> List<WindowBounds>` for tests.
   - New `enum class DayTrackingReason { NONE, OUTSIDE_SCHEDULE, NOT_YET }` plus, for NOT_YET, the next start ms. For the selected day's bounds (the VM already computes day bounds for items):
     - no active intervals that day → `OUTSIDE_SCHEDULE`;
     - today and the first interval still starts after now → `NOT_YET(startMs)`;
     - otherwise `NONE`.
   - Recompute on date change and when the schedule changes: observe `ScheduleRepository.observeTemplate()` / `observeOverride(monday)` the way `ui/settings/SettingsViewModel.kt` ~60-65 does (and manual overrides if a flow exists). Use the VM's clock, not `System.currentTimeMillis()`.
2. **UI** (`ui/timeline/TimelineContent.kt` ~225-229, `EmptyState` with `testTag("timeline_empty")`): when the day is empty, add one reason line under the existing body:
   - OUTSIDE_SCHEDULE → "Not tracking: outside your schedule"
   - NOT_YET → "Tracking starts at %s" with the shared time formatter (`rememberTimeFormats()`), same 12/24 h rules as the Timeline.
   - NONE → nothing extra. Keep the existing titles/bodies. Strings in `strings_timeline.xml`, sentence case. Font 2.0 must not truncate.

## Tests
- VM: unscheduled day → OUTSIDE_SCHEDULE; today before the window → NOT_YET with the right start; today inside/after window → NONE; past scheduled day → NONE; schedule change re-emits.
- Compose (`TimelineContentTest`): each reason line shown only when empty; not shown when items exist; font 2.0 not truncated.

## Rules
No Room schema change. Small diffs. `gradlew.bat testDebugUnitTest lintDebug assembleDebug` green. Commits by item, explicit paths (include this handoff in the first), never `.idea/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`. Report files, tests, decisions, skips.
