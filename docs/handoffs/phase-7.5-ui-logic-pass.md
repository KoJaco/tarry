# Handoff: phase 7.5 — UI and logic pass (launch-ready)

**From:** Claude (planner, session `tarry-phase-6`) → **To:** the owner, working with Claude sessions back and forth · **Date:** 2026-10-31 · **Branch to create:** `feat/phase-7.5-ui-pass` from `main`, after phase 7 merges. Sub-branches per area are fine, e.g. `feat/phase-7.5-timeline`, each merged into the 7.5 branch or straight to `main`.

## Why this phase exists
Phases 2c–7 built the features with "correct and complete first, the design pass at the end" (owner, 2026-10-28). Phase 7.5 is that pass. Its job is to:
- get every existing screen to launch quality;
- tidy the logic behind them;
- **write down the rules**, so phase 8 builds onboarding, privacy, permissions, paywall and help screens on the same system, without re-deciding anything.

This phase is **owner-driven and iterative.** The owner looks at a screen on the Pixel, says what's wrong, and a session fixes it, again and again. So unlike the other phases, there is no fixed task list up front, only areas, guardrails and the deliverables below.

## Deliverables (what "done" leaves behind)
1. **`docs/design/tokens.md`, updated.** It is the source of truth for colour, type, space, shape, motion and components, so every change to the look lands here and in `TarryTheme` together.
2. **`docs/design/ui-rules.md`, new.** It holds the rules phase 8 must follow:
   - **screen anatomy:** title, intro line, sections, primary action placement, sheet versus full screen;
   - **writing:** voice and tone, sentence case, how to word errors, empty states, permissions and confirmations; numbers and times;
   - **component use:** which button for which action, when to use a sheet, dialog or snackbar, and undo rules;
   - **motion and haptics;**
   - **accessibility floor:** 48dp targets, TalkBack phrasing, contrast, font scale 2, reduced motion;
   - **illustration and icon policy:** the current rule is no illustrations, Material Symbols Rounded 300. Keep or change it, but write it down;
   - **a "how to build a new screen" checklist.**
3. **Every existing screen passes `ui-reviewer`.** It checks screenshots in light and dark, font scale 1 and 2, and TalkBack. The screens are Timeline (Day, Map, Summary), the drawer kinds, Schedule, Jobs, Places and place detail, Settings, Sessions, Permissions, and the tracking sheets.
4. **A logic tidy-up with no behaviour change unless agreed.** Every change still ships with a test (rule 7).

## Areas to walk through (suggested order)
1. **Timeline day:**
   - two-column canvas density;
   - the job column at font scale 2 (today `jobFraction = 0.4 / fontScale` with a 48dp minimum, so names get cut);
   - tracking-marker labels at 2× (they wrap, but `placeTrackingMarkers` assumes one line);
   - suggestion dashed-outline contrast (it may be under 3:1 in light theme);
   - edge grips and drag affordances;
   - review row (Day | Map | Add job).
2. **Drawer:** visit, trip, job, new job and new visit. Section order, the Job section chips, "Default job for <place>" wording, and when it saves.
3. **Summary and export** (from phase 7): table density, range picker, the Unlabelled row, and the export sheet.
4. **Map:**
   - phase 5 nits, listed in `docs/handoffs/phase-6-schedule.md` § Carried-over;
   - the faint mini-map credit in dark mode;
   - the light/dark switch re-centring the map;
   - "New place here" saving at (20, 0) if the user never pans.
5. **Schedule grid:** cells are about 42–44dp wide, a known deviation from the 48dp rule, and the day sheet.
6. **Settings hub, Jobs, Places, Sessions, Permissions, tracking sheets.**
7. **Icons:** the four hand-drawn icons (`ic_map`, `ic_work`, `ic_location_pin`, `ic_delete`) against the Material Symbols set.
8. **Logic tidy-up candidates, each only if worth it, and listed before doing:**
   - manual-visit Save in-flight guard;
   - `TarryMap` MapView keyed on `LifecycleOwner`;
   - `TimelineViewModel` size: split the day, map and job-block flows if it helps readability;
   - `firstFreeSlot` and other leftovers;
   - unused resources (`ic_export`, `sessions_export_csv`, `sessions_export_gpx`, `timeline_trip_row`);
   - stale KDoc;
   - the `place_defaults_hint` wording.

## Guardrails
- **Hard rules still apply,** from AGENTS.md:
  - no network (rule 2);
  - tokens through `TarryTheme`, never literals in screens (rule 11);
  - WCAG 2.2 AA;
  - tests for behaviour changes (rule 7);
  - small diffs, with unrelated problems listed rather than fixed (rule 9).
- **Design changes come from the owner.** A session proposes options (the `frontend-design` and `my-design` skills, ideally side-by-side mock-ups as an artifact) and the owner picks. Don't restyle on your own judgement.
- **Brand:**
  - **The brand handoff is a starting point:** `docs/brand/brand-and-copy.md` is used for the landing page, so if 7.5 changes colours, type or voice, update it in the same PR so the app and the site don't drift;
  - **Fixed palette:** keep it (no dynamic colour), because tracking-state and job colours must stay predictable.
- **Before installing,** back up the phone DB to `tmp/` and install only when the owner is idle.

## Definition of done
- [ ] `tokens.md` and the new `ui-rules.md` describe what ships, and the brand doc matches them.
- [ ] Every screen in deliverable 3 has passed `ui-reviewer`, with findings fixed or explicitly accepted by the owner.
- [ ] The logic tidy-up list is done or deferred with a reason.
- [ ] `gradlew.bat assembleDebug testDebugUnitTest lintDebug` passes with no new lint warnings, and CI passes.
- [ ] The owner signs off on the Pixel, light and dark.
