# Handoff: phase 7 — Summary and export

**From:** Claude (planner, session `tarry-phase-6`) → **To:** next Claude session (planner first, then subagents) · **Date:** 2026-10-29, updated 2026-10-31 · **Branch to create:** `feat/phase-7-summary-export` from `main`, **after phase 6.5 merges** (`docs/handoffs/phase-6.5-trust-pass.md`)

## Where things stand
- **Merged on `main`:**
  - phase 6, the schedule grid and the toggle long-press sheet (PR #23, spec `docs/specs/phase-6.md`);
  - phase 6b, job blocks with tags removed (PR #24, spec `docs/specs/phase-6b.md`);
  - the 6b follow-up (PR #25): suggestions open on double tap with Accept or Decline, declines persist in `DeclinedSuggestionStore`, and undo for "Add to job", "Assign job" and move/resize is atomic via `JobBlockRepository.revert`;
  - phase 6.5, the trust pass (check that it has merged).
- **The DB is Room v2.** The owner's phone migrated cleanly on 2026-10-28:
  - every count was preserved and 11 job blocks were created;
  - backups are in `tmp/pre-phase-6b-install/` (v1) and `tmp/post-phase-6b-install/` (v2);
  - `RealDbMigrationTest` (skipped unless `TARRY_REAL_DB` is set) is the pattern for checking a migration against a copy of the real DB.
- **Dogfood:** the owner tracked the full week of 26–30 Oct and is happy with the data (2026-10-31). Use a pulled copy in `tmp/` for summary sanity checks against real weeks, never in the repo.
- **Where this sits in the MVP** (build plan, updated 2026-10-31):
  - 6.5 trust pass → **7 summary and export** → 7.5 UI and logic pass (launch-ready UI, owner-driven) → 8 launch (onboarding, Play Store, Play Billing).
  - **Pricing (phase 8):** a 7-day free trial, then a one-time lifetime purchase (about 29 AUD, to be confirmed) through Google Play Billing. Build phase 7 **without** gating. Phase 8 decides what the trial end locks. Keep the summary and export code free of entitlement checks so phase 8 can wrap them in one place.
- **Read these first:**
  - `AGENTS.md`;
  - `docs/specs/build-plan.md`: the phase 7 line and the **Spec deltas**, especially the job blocks and no-tags deltas, which override the MVP spec;
  - `docs/specs/tarry-mvp.md`, § Summary, rounding and export (line ~265), § Timeline → Summary view, and § Settings → Billing and → Data. Sections that mention tags, per-visit job or billable, or travel attribution are historical; see the note at the top of § Data model;
  - `docs/specs/phase-6b.md`;
  - `docs/design/tokens.md`.

## Goal
Turn the labelled timeline into invoiceable numbers, and get them out of the app:
- a **Summary** of job blocks over a date range, with rounding and the minimum billable rule;
- **CSV** export (a stopgap for Walkflow, so it carries stable IDs);
- **PDF** export (one page per job);
- **JSON backup and restore** of everything, through the Storage Access Framework.

## The model phase 7 sums (from 6b, don't rebuild)
- **`JobBlock`** (`core-data/.../db/entity/`): `id` UUID, `jobId`, `start`, `end`, `zoneId`, `billable`, `notes`, `updatedAt`, `deleted`. Read it through `JobBlockRepository`: `observeOverlapping(from, to)`, `overlapping`, `save`, `replace`, `delete`, `restore`.
- **Billed time = the block's range** (owner decision, like a timesheet entry). Visits and trips inside a block are evidence only.
- **Blocks of different jobs may overlap**, and each bills its full range. A day's total across jobs can therefore exceed wall-clock time; the summary must not present that sum as "time worked". **Blocks of the same job never overlap**: every write merges them (`mergeSameJob`).
- **`Job`** has `name`, `colorArgb`, `archived` and `defaultBillable`. **`Place.defaultJobId`** only drives suggestions, which are never billed.
- **"Needs review" / unlabelled** = a visit at a non-ignored place that no block covers (`coverage` in `app/.../ui/timeline/JobBlocks.kt`).
- **Settings already in DataStore** (`core-data/.../settings/Settings.kt`): `roundingIncrementMin` (0, 6, 15, 30), `roundingMode` (NEAREST, UP) and `minBillableMinutes`. Settings → Billing currently only *displays* them (`SettingsScreen.kt` ~:94), so check whether they can be edited. `TravelAttribution` is gone.
- **Existing export:** `app/.../export/RawExporter.kt` and `RawPingFormats.kt` (raw pings GPX/CSV via SAF, `ExportRequest`) is the pattern for SAF file writing.
- **Day summary card:** `ui/timeline/DaySummary.kt` and `SummaryCard.kt`. Check what it sums now that visits carry no job.

## Scope
**Build order (owner agreed 2026-10-31):**
1. backup and restore, which protects the dogfood data, today backed up only by `adb` pulls;
2. Billing settings made editable;
3. Summary calculation and view;
4. CSV;
5. PDF.

The numbered items below are by topic, not by order.

1. **Summary view:**
   - date range presets: Today, Yesterday, This week, Last week, Custom;
   - grouped by job (with toggles for by day, and possibly by place, see questions);
   - per group: block count, total time, billable time after rounding and the minimum;
   - an **"Unlabelled"** row for on-site time no block covers, so nothing is lost;
   - tap a row to see its blocks, with drill-through to the timeline day;
   - export button for the current range and grouping.
2. **Calculation (pure, JVM-tested):**
   - clip blocks at the range boundaries and at local midnight, DST-safe;
   - round per block, then sum, with an option to round the daily total per job instead;
   - minimum billable;
   - billable versus non-billable totals;
   - archived jobs still summed;
   - deleted blocks and deleted jobs excluded.
   - Golden tests are needed (MVP § Testing names "summary math").
3. **CSV:** the MVP's `tags` column is gone. Rows probably become one per job block, with stable `job_block_id`, `job_id` and local times with an ISO offset. Decide whether segment rows (visits and trips with the ids of the blocks covering them) go in the same file or a second one; Walkflow needs stable IDs.
4. **PDF:** one page per job: header (job, range, totals), a table of blocks with notes, and optionally the covered visits. Use Android `PdfDocument`, since no new dependency and no network are allowed (rule 2).
5. **Backup and restore:**
   - one versioned JSON of all entities and settings, saved through SAF;
   - import validates the schema version and offers replace (and merge?);
   - decide whether raw pings are included (large, but it is a user-initiated export, so allowed);
   - restore must never half-apply (one transaction), and must handle a backup from an older schema version.
6. **Settings → Billing:** make rounding increment, rounding mode and minimum billable editable, if they aren't already.

## Things to decide with the owner while planning (don't guess, rule 12)
- **Minimum billable:** does it now apply per job block (a 10-minute block under a 15-minute minimum is shown but not billed)? Or per job per day?
- **Rounding:** per block, or the daily total per job as the default? And how rounding interacts with overlapping jobs.
- **Overlaps in totals:** how to show the grand total across jobs, for example "Billed 9 h 30 · on the clock 7 h", or per-job totals only.
- **Unlabelled row:** visit time only, or trips not covered by a block too? Ignored places are excluded either way.
- **Where Summary lives:** a third segment next to Day | Map (the review row toggle), its own tab, or a screen from the day header? The timeline is per-day with a week strip, but the summary needs a range.
- **Money:** hours only (the MVP has no rates), or a per-job rate? Rates would be a schema change (Room v3 via `migration-guard`).
- **CSV shape** for Walkflow (above).
- **Backup:** replace only for the MVP, or merge too? Merging needs `updatedAt`-wins rules per entity.
- **Exports in summary terms:** clip a block that crosses the range edge, or keep it whole?

## Hard constraints (AGENTS.md)
- **Plan first** (rule 8) with the `plan-feature` skill in plan mode. This touches more than 3 files and possibly the schema.
- **Privacy** (rule 2): no network. Files leave only through user-picked SAF locations, and backup stays out of Android auto-backup (`allowBackup=false` is already set).
- **Room** (rule 4): a schema change (e.g. rates) goes through `migration-guard` with a `MigrationTestHelper` test. Test it against a *copy* of the real DB (`RealDbMigrationTest` pattern), and back up the phone DB to `tmp/` before installing.
- **Tests** (rule 7): golden tests for summary math (rounding modes, minimum billable, clipping at range and midnight, DST days, overlaps, archived and deleted jobs), CSV and JSON round-trip tests, a backup import replace test, and Compose tests for the Summary view.
- **Real data never enters the repo.** Exported files and DB pulls stay in `tmp/`, and fixtures are synthetic.
- **Design:** the owner does a full design pass at the end of the MVP, so focus on correct, complete logic and UX. Tokens still apply, as do touch targets of at least 48dp and TalkBack.

## Carried-over follow-ups (not phase 7; list them, don't fix them unasked)
- **Phase 6.5** handles the 24 Oct no-Gap drive, the lifecycle pass and the battery measurement.
- **Phase 7.5** (the UI and logic pass) collects the visual and refactor items:
  - suggestion outline contrast;
  - job column at font scale 2;
  - tracking-marker labels at 2×;
  - hand-drawn icons;
  - `place_defaults_hint` wording;
  - unused resources (`ic_export`, `sessions_export_csv`, `sessions_export_gpx`, `timeline_trip_row`);
  - the phase 5 nits in `docs/handoffs/phase-6-schedule.md`.
- **Never answered:** the owner's day-3 "tiny tweaks". Ask once.
