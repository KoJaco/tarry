# Handoff: phase 6.5 — Trust pass (capture bug, lifecycle, battery)

**From:** Claude (planner, session `tarry-phase-6`) → **To:** next Claude session (planner first, then subagents) · **Date:** 2026-10-31 · **Branch to create:** `fix/phase-6.5-trust-pass` from `main` (one worktree)

## Where things stand
- **On `main`:** phases 0–6b. Phase 6b's follow-up is merged too: suggestion review, and undo for add and assign (PR #25).
- **The DB is Room v2,** with job blocks and no tags.
- **The owner dogfooded the full week of 26–30 Oct** and is happy with the data. A day-by-day hole audit is therefore optional: do it only if the bug below needs more evidence.
- **Phase 7** (Summary and export) follows this phase. Its handoff is `docs/handoffs/phase-7-summary-export.md`.

## Goal
Before the app's numbers are used for invoicing, prove that tracking either records or visibly says it didn't. To do that:
- fix the one known silent hole;
- run the spec's lifecycle tests deliberately, once each;
- take one battery measurement.

## Scope
1. **The 24 Oct no-Gap drive (rule 6 breach). Do this first, with the `debug-triage` skill.**
   - **What happened:** on 24 Oct, 11:17–11:28 AEDT, the owner drove about 2.8 km. Tracking recorded no fixes, and no Gap was recorded. Fixes before and after were GPS grade (8–30 m).
   - **Background:** `docs/specs/phase-5.md` § Device-pass revisions.
   - **The data:** use the 24 Oct rows of the owner's DB, which must stay in `tmp/` (e.g. `tmp/device-pull-2/` or the `tmp/pre-phase-6b*/` backups; those are v1, open read-only copies). Times in queries are AEDT (`'+11 hours'`).
   - **Suspects to check:**
     - activity-transition gating, i.e. the driving→still and still→driving switch to low power or idle capture modes;
     - the stay geofence (the exit was never seen);
     - fused location request pausing;
     - the health monitor's gap rule (why no fix for 11 minutes during an open session didn't become a Gap);
     - Doze or app standby.
   - **The fix** must make an equivalent outage surface as a Gap (or ATTENTION), and ideally stop the outage from happening.
   - **Regression tests:** one in tracking or the health monitor, plus a segmentation golden case if segmentation is involved. Use a synthetic trace that recreates the shape; real traces never go in the repo.
2. **Lifecycle test pass (MVP § Testing).** Run each case once on the Pixel and write down the result. Each case must end in correct tracking, or a visible Gap or ATTENTION.

   | Case | How to trigger | Expected |
   |---|---|---|
   | Reboot mid-window | `adb reboot` during a window | Tracking resumes after boot. The off period is a Gap. |
   | Time-zone change | Change the zone in settings mid-window | Windows follow the local schedule. No crash, no double session. |
   | DST change | Device time near a DST edge (or a JVM test if the device can't simulate it) | Windows are correct. A 23 h or 25 h day renders. |
   | App update mid-session | `installDebug` while tracking | The session ends KILLED, a short Gap is recorded, and the update receiver restarts capture. |
   | Permission revoked mid-window | Revoke background location | ATTENTION is shown. The Gap is bounded and capture resumes on re-grant. |
   | Location toggled off | Quick settings | ATTENTION, and resume. |
   | Doze | `adb shell dumpsys deviceidle force-idle` | The exact alarm still fires at the next edge. Note any fix starvation. |
   | Force-stop | Settings → Force stop | Make sure the owner understands what happens: Android blocks alarms until the next launch. Next launch reconciles, and the hole is a Gap. |

   Write a test for each bug found, and fix only what fails. Record the results table in `docs/specs/phase-6.5.md`.
3. **Battery:**
   - Compare one normal tracked work day with a baseline (untracked) day, using `adb shell dumpsys batterystats` or the Android battery usage screen. Record the drain per hour in `docs/specs/phase-6.5.md`.
   - Owner decision (memory: accuracy first): GPS stays always on in sessions. This is a measurement only, not tuning.
4. **Small known items (only if quick):**
   - `DayCanvas.kt:553` "Condition is always true";
   - the vacuous create-preview `assertDoesNotExist` test in `JobColumnTest` (`clearAndSetSemantics` comes before `testTag`);
   - a missing test that the job column is disabled in multi-select.

## Out of scope
- New features and UI polish: those belong to phase 7.5.
- Battery tuning.
- Samsung or other OEM testing: the owner only has a Pixel. List it as a known gap.

## Hard constraints (AGENTS.md)
- **Rule 5 (no polling):** the fix must not add a timer. The OS wakes the app (alarms, geofences, activity transitions, WorkManager), and any new tunable goes in `TrackingConfig`.
- **Rule 6:** the whole point of this phase is that any state where tracking should run but isn't surfaces as ATTENTION or a Gap.
- **Rule 7:** a regression test first, then the fix. Tracking-service changes are plan-first (rule 8).
- **Rule 4:** a schema change is not expected. If one is needed, use `migration-guard` and test against a copy of the real DB with the `RealDbMigrationTest` pattern.
- **Device safety:**
  - back up the phone DB to `tmp/` before any install;
  - install only when the owner is idle (an install kills a live session);
  - ask before reboot or force-stop tests, since they disturb the owner's day.

## Definition of done
- [ ] The 24 Oct outage shape is reproduced in a test and fixed, and an equivalent outage now yields a Gap.
- [ ] The lifecycle table is filled in, with every row either passing or fixed with a test.
- [ ] Battery drain is recorded.
- [ ] `gradlew.bat assembleDebug testDebugUnitTest lintDebug` and CI pass. PR to `main`, and the owner merges.
