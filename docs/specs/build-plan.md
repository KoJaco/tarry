# Tarry: build plan (v1)

Approved 2026-10-20. Source spec: `docs/specs/tarry-mvp.md`. Where they conflict, this file wins.

## Decisions
| Topic | Decision |
|---|---|
| MVP "done" | Signed APK sideloaded onto the phone. Play Store work moves post-MVP |
| Modules | `:segmentation` (pure Kotlin/JVM), `:core-data` (Room + DataStore), `:app` (tracking, UI, export as packages) |
| Map tiles | OpenFreeMap via MapLibre Native |
| Licence | GPL-3.0 |
| Dogfood device | Pixel. OEM guidance stays generic |
| Executor | Claude subagents (implementer → test-writer → reviewer); owner tests on device |
| Priority | Tracking ASAP, with early segmentation in the first phone build |
| Export consumer | Walkflow. CSV is a stopgap until Pro sync/MCP, so it carries stable IDs |
| Design | Fill `docs/design/tokens.md` and port to a custom Material 3 theme |
| Repo | Private GitHub repo with CI and branch protection; public later |
| Jobs | First-class Job entity (UUID, name, colour, archived) instead of free-text labels |
| Same-site jobs | Common: split-visit and place radius in the first drawer release |
| Backup | Versioned JSON via the Storage Access Framework |
| Needs review | Visit at a non-ignored place whose *resolved* job is null |

## Spec deltas (intentional)
- **IDs:** UUID + `updatedAt` + `deleted` on Place, Visit, Trip, Job, Tag, TagGroup. Long keys on LocationPing and TrackingSession only.
- **Job entity:** `Visit.jobId?`, `Trip.attributedJobId?`, `Place.defaultJobId?` replace the `jobLabel` strings. Drawer gets a job picker with inline create.
- **Place default tags:** `PlaceDefaultTag` join table instead of a list column.
- **New tables:** `ExcludedRange` (deleted visits), `Gap` (health-monitor outages and segmentation), `SegmentationCheckpoint`.
- **CSV columns:** spec columns plus `segment_id` and `job_id`.
- **Schedule alarms:** exact alarm (`SCHEDULE_EXACT_ALARM`, user-granted) for window starts, since only exact alarms exempt background foreground-service starts on Android 12+. Validated in the phase 2a spike; fallback is the tap-to-start notification.

## Phases
Each phase: own branch, short spec in `docs/specs/phase-N.md`, implementer → test-writer → reviewer, PR with green CI, device check.

**Status (2026-10-25):** phase 5 (Map) is done (spec `docs/specs/phase-5.md`); offline regions and map matching are deferred. Next: **phase 6, schedule grid + manual controls UI**. Handoff: `docs/handoffs/phase-6-schedule.md`.

**Status (2026-10-24):** phase 4 (#14, #15) and the dogfood-2/3 fixes (#16, `docs/specs/fix-dogfood-2.md`) are merged. Next: **phase 5, Map**. Handoff: `docs/handoffs/phase-5-map.md`.

**Status (2026-10-22):** phase 3 is merged (PRs #10–#13), except T4 multi-select, which moves to phase 4. Next: **phase 4** (spec `docs/specs/phase-4.md`), shipped as 4a (selection, jobs, tags; handoff `docs/handoffs/phase-4a-selection-catalog.md`) then 4b (places).

**Status (2026-10-21):** 0, 1, 2a (PR #4, dogfood fixes #5), 2b (PR #6) and 2c (design system, PR #8) are merged. Next: the owner dogfoods a day on the 2c build, then **phase 3, timeline editing** (spec `docs/specs/phase-3.md`, handoff `docs/handoffs/phase-3-timeline-editing.md`), then 4–8.

0. **Repo and conventions (S):** git, `.gitignore`, GPL-3.0 `LICENSE`, Android AGENTS.md, ADR 0001, retarget agents, fix hooks, private GitHub repo + CI + branch protection.
1. **Foundation (M):** update version catalog; KSP, Room, Hilt, DataStore, WorkManager, Navigation, coroutines, Play Services location (verify Hilt/KSP with AGP 9 first). `:segmentation` and `:core-data` modules, Room v1 with all entities + deltas and exported schema. Hilt app, bottom bar (Timeline | toggle | Settings). Manifest permissions, backup disabled for the DB.
2. **Tracking core + early segmentation (L):**
   - **2a Capture:** spike the alarm-triggered FGS start first. Foreground service, fused location, activity transitions, stay geofence, capture modes and filters in `TrackingConfig`. Schedule engine with hard-coded Mon–Fri 07:00–17:00, exact alarm, boot/time/update receivers. Manual start/stop/pause, notification actions. Health monitor, ATTENTION, Gap rows. Minimal permission flow. GPX/CSV raw export and debug sessions screen. Install on phone immediately.
   - **2b Segmentation:** pure stay/trip/merge/place/gap logic with frozen and excluded ranges. Synthetic trace generator and golden cases. SegmentationWorker with checkpoint and overlap, geocoder queue. Crude day list.
   - **2c Design system (M):** fill `docs/design/tokens.md` for Android (colour roles light/dark, type, space, shape, motion, tracking-state looks), build the M3 theme and shared components, restyle the existing screens, ui-review. Spec: `docs/specs/phase-2c.md`.
3. **Timeline editing + edit drawer (L):** (design tokens and theme moved to 2c; the 2c calendar canvas replaces the list view) Totals, cards, needs-review dot, filter, badge. Drawer: job picker, tags, billable, notes, trim, split, merge, reassign place, delete, reset to detected, freeze, undo. Manual visit, multi-select bulk actions. Compose tests.
4. **Jobs, tags and places management (M):** jobs CRUD, tag groups/tags CRUD with reorder and archive, places list with defaults, radius slider, ignore, merge, delete.
5. **Map (L):** MapLibre + OpenFreeMap, route and numbered pins, scrubber, date-time jump, mini maps, radius preview, map-pin manual visit, offline regions (check OpenFreeMap terms).
6. **Schedule grid + manual controls UI (M):** 7×48 paint grid, copy day, presets, template vs override, toggle long-press sheet.
7. **Summary and export (M):** grouping, rounding, minimum billable, attribution, clipping, unlabelled row. CSV, PDF, JSON backup/restore. Summary-maths unit tests.
8. **Onboarding and hardening (M):** full onboarding, diagnostics, self-check, review reminder, retention, delete-all, About, release signing, GitHub Releases, lifecycle test pass.

**Post-MVP:** Play Store submission, F-Droid flavour, Pro tier (derived-data sync, MCP, Walkflow).

## Verification
- Every PR: `gradlew.bat assembleDebug testDebugUnitTest lintDebug`, same in CI.
- Room: exported schema committed; migration tests from v1.
- Phase 2: a real day on the Pixel; forced Doze, reboot mid-window, revoked permission → ATTENTION. Real traces never enter the repo (it is public): exports stay in the gitignored `tmp/`, and segmentation tests use synthetic traces only.

## Risks
- Background FGS start from an alarm on Android 12+ / targetSdk 37 (spike first).
- Hilt/KSP with AGP 9 built-in Kotlin (fallback: manual DI).
- OpenFreeMap offline terms (fallback: PMTiles).
- Battery drain (tune `TrackingConfig` from dogfood logs).

## Defaults
- Hard-coded 2a schedule: Mon–Fri 07:00–17:00 local.
- Trips can carry tags and notes.
- Gaps are stored rows so cause text survives.
