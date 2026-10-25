# Handoff: phase 6 — Schedule grid + manual controls UI

**From:** Claude (planner, session `tarry-phase-5`) → **To:** next Claude session (planner first, then subagents) · **Date:** 2026-10-25 · **Branch to create:** `feat/phase-6-schedule` from `main`

## Where things stand
- **Phase 5 (Map)** is on `feat/phase-5-map`, going to `main` as one PR. Spec and device pass: `docs/specs/phase-5.md`. The DB is still Room v1.
- **The owner dogfoods the full work week from 2026-10-26 (Mon–Fri)** on the phase 5 build. The schedule is still the seeded Mon–Fri 07:00–17:00 template, because there is no UI to change it yet.
  - At the start of the session, ask how the week went. Pull the DB to `tmp/` (never into the repo) and check each day for holes, and whether each hole was recorded as a Gap.
  - Pull: `MSYS_NO_PATHCONV=1 adb exec-out run-as me.tarry.app cat databases/tarry.db > tmp/<dir>/tarry.db`. Do the same for `-wal` and `-shm`. Times in the queries are AEDT (`'+11 hours'`).
- **2026-10-25 (Sunday, a few hours, manual session):** two short drives, both fully covered with fixes within 50 m. The service was killed at 12:56:59 (`endReason` KILLED); a `SERVICE_KILLED` Gap was recorded and the owner restarted it by hand. The cause of the kill is unknown, so watch for repeats during the week.
- **Open capture bug (outside phase 6, do it first or in parallel):** on 24 Oct, 11:17–11:28, the owner drove about 2.8 km with no fixes and **no Gap** recorded. That breaks AGENTS.md rule 6. Use the `debug-triage` skill on the tracking side. Details are in `docs/specs/phase-5.md` § Device-pass revisions.
- **Read these first:**
  - `AGENTS.md`;
  - `docs/specs/build-plan.md`, the phase 6 line;
  - `docs/specs/tarry-mvp.md` § Schedule (data model), § Schedule engine, § Manual controls, § Bottom bar and tracking toggle, and § Settings → Schedule (line 248);
  - `docs/decisions/` (ADR 0003 covers the exact-alarm start);
  - `docs/design/tokens.md`.

## Goal
Let the owner paint the weekly schedule once and then forget about it ("set and forget"), and give the tracking toggle its quick controls. This replaces the hard-coded Mon–Fri 07:00–17:00 preset.

## Scope (build plan phase 6 plus the MVP spec)
1. **Schedule grid** in Settings → Schedule:
   - 7 columns × 48 half-hour rows; 06:00–20:00 visible by default, and it scrolls;
   - drag to paint or erase (the first cell touched decides which);
   - tap a day header to copy that day to other days;
   - presets: Mon–Fri 07:00–17:00, and Clear.
2. **Template vs override:**
   - a toggle at the top between *Every week* (template) and *This week only* (override);
   - week navigation for overrides;
   - a way to drop an override and go back to the template.
3. **Toggle long-press sheet:** a quick sheet with the duration options. Today, long-press only does the same as a tap (`TrackingToggle.kt:125`). Decide with the owner what the long-press sheet shows that the tap sheet doesn't.
4. **Manual controls:** start (until stopped, end of day, duration) and pause (30 min, 1 h, until next window) already exist in `TrackingSheet.kt`. Check them against the MVP spec and fill any holes. Don't rebuild them.

## What already exists (don't rebuild)
- **Model:** `core-data/.../model/Schedule.kt` is a 336-bit immutable bitmap with `set`, `isActive`, `toBytes`/`fromBytes` and `MON_FRI_7_TO_17`.
- **Storage:** `ScheduleRepository` (in `Repositories.kt`) has `template`, `observeTemplate`, `saveTemplate`, `override(weekStart)`, `saveOverride` and `clearOverride`. Tables `schedule_template` and `schedule_override` are in Room v1, so **no schema change is expected**.
- **Engine:** `EffectiveScheduleCalculator`, `ScheduleInputsProvider` (seeds the preset on first launch), `ScheduleAlarmScheduler` and `TrackingController.reconcile(ReconcileSource)`.
- **Manual overrides:** `ManualOverrideType` / `ManualOverrideEndMode`, `TrackingController.start/pause/resume/stop`.

## Things to decide or check while planning
- **Saving a schedule has to re-arm the alarm** and reconcile at once, e.g. `reconcile(ReconcileSource.UI)`. Today nothing but the first-launch seed writes a template, so check whether anything observes it. Edge cases:
  - painting out the window you're in right now: stop, or wait for the next edge? Ask the owner;
  - painting in a window that has already started;
  - an override for the current week.
- **Time-zone and DST:** slots are local time. Check the calculator's DST handling with the grid in mind (a 30-minute slot that doesn't exist or happens twice).
- **Accessibility (WCAG 2.5.7):** drag-to-paint needs a non-drag alternative, e.g. tap a cell to toggle it, plus per-day "set hours" pickers. Cells must be reachable with TalkBack, and 48 dp touch targets mean the grid can't show 48 rows at once (hence the scrolling 06:00–20:00 view). Font scale 2.
- **Where Schedule lives in Settings** and how it shows the current week's effective schedule.
- **Onboarding (phase 8)** will offer "paint the schedule (preset offered)". Build the grid so onboarding can reuse it.

## Hard constraints (from AGENTS.md)
- **Plan first** (rule 8): this touches the tracking engine's inputs and more than 3 files. Use the `plan-feature` skill in plan mode.
- **Tracking (rule 5):** no polling. Schedule changes re-arm alarms; nothing runs on its own timer.
- **Never fail silently (rule 6):** an edit that leaves tracking in the wrong state must surface as ATTENTION or a Gap.
- **Room (rule 4):** if a schema change turns out to be needed, it goes through `migration-guard` with a migration and a `MigrationTestHelper` test.
- **Tests (rule 7):** pure grid-paint and copy-day logic with JVM tests, a Compose test for painting (MVP § Testing names "schedule grid painting"), and tests for re-arming on save.
- **Real traces never enter the repo.** Week-long dogfood data stays in `tmp/`.

## Carried-over follow-ups (not phase 6; list them, don't fix them unasked)
- Phase 5 known items: the mini map credit is faint in dark mode; the manual-visit error stays until the next save; `instantNear` can put an edited time on the adjacent day; the pin picker falls back to a world view when there's no route.
- Map matching and offline regions are post-MVP / deferred (`docs/specs/phase-5.md`).
- Check a water trip on the new GPS build (the 23 Oct boat hole came from a network-fix-only day).
- The owner's day-3 "tiny tweaks" were never reported. Ask once.
