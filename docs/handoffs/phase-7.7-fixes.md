# Handoff: phase 7.7 — fixes, icons and tidy-up (7.5 areas 4–8)

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-08 · **Branch:** `feat/phase-7.7-ui-finish`. Do this after `phase-7.7-drawer.md`. The same rules apply (one commit per item, explicit paths, a test per behaviour change, no instrumented tests while the Pixel is attached, no push). The owner approved every item below on 8 Nov 2026.

## Map bugs
- **F1. "New place here" at (20, 0).** If the user never pans, the pin picker saves a meaningless coordinate.
  - Find where the pin's initial value comes from (`PinPickerDialog.kt`, the drawer state).
  - The pin starts at the map's actual camera target once the map is ready.
  - Save stays disabled until a real position exists, either from the camera or from the route/visit centre used to frame the map.
  - Test the state logic on the JVM.
- **F2. A light/dark switch re-centres the day map.**
  - A theme change reloads the style (`TarryMap` `LaunchedEffect(mapView, styleJson)`), and `DayMapView` then re-frames on the marker.
  - Keep the user's camera across a style reload: re-frame only when the day, data or marker changes, not on a style change.
  - Test whatever decides "should re-frame".
- **F3. The mini-map credit is faint in dark mode.**
  - Find the mini-map attribution (radius sheet / place detail preview).
  - It must reach 4.5:1 for its text in both themes, using tokens (e.g. `ink` on `paper` at 0.85 alpha, like `MapAttribution`).

## Manual visit save
- **F4. In-flight guard.** A double tap on Save in the new-visit form can flash an "Overlap" error. Ignore further saves while one is in flight (in the ViewModel, not only by disabling the button). Unit test: two quick saves give one insert and no error.
- **F5. Stale error.** The manual-visit error stays until the next save. Clear it as soon as the user changes the start, end or place. Unit test.
- **F6. `instantNear` can put an edited time on the adjacent day.** Find `instantNear`. An edited time-of-day must resolve on the segment's own local date (the capture zone). It must not move into the previous or next day unless the user explicitly crosses midnight, if that is supported (check how it works today and keep it).
  - Unit tests: times near midnight, and a DST change day in `Australia/Sydney`.

## Icons
- **F7. Replace the four hand-drawn icons** (`ic_map`, `ic_work`, `ic_location_pin`, `ic_delete`) with Material Symbols Rounded vector drawables, weight 300, grade 0, optical size 24, matching the other `ic_*` files.
  - Same resource names, so call sites are unchanged.
  - Copy the path data from the official Material Symbols SVGs (map, work, location_on, delete). Use the same source and format as the existing Symbols icons in `res/drawable`. Look at one first.
  - If you cannot get the official path data offline, stop and report rather than drawing your own.

## Code tidy (no visible change)
- **F8. `TarryMap`:** key the remembered `MapView` (and its `DisposableEffect`) on the `LifecycleOwner` too, so a new owner gets a fresh, correctly lifecycled view.
- **F9. Stale KDoc:** fix `TimelineViewModel.dayMap`'s KDoc to say what the code does (two subscriptions). Fix any other KDoc you touch in this branch that no longer matches.
- **F10. Unused resources:** run `gradlew.bat lintDebug` and delete only what lint reports as `UnusedResources` (strings, drawables). Note that `ic_export`, `sessions_export_*_row` and `timeline_trip_row_*` are used. List what was removed in the commit message.
- **F11. Leftovers:** `firstFreeSlot` and similar dead code. Remove it only if nothing references it. Otherwise list it in your report.

## Report
Commits (hash + subject), test count/result, new lint warnings, and anything skipped with the reason.
