# Handoff: phase 7, steps 4–5 (CSV and PDF export)

**From:** Claude (planner, session `tarry-phase-6.5`) → **To:** a fresh Claude session (planner, implementing with the `.claude/agents/` subagents) · **Date:** 2026-11-03 · **Branch/worktree:** `feat/phase-7-summary-export` in `.claude/worktrees/phase-7` (not pushed yet)

## Where things stand
- **Main** has phase 6.5 merged: PR #26 (handoff docs) and #27 (trust pass), at `f50eaf1`.
- **This branch**, rebased on main and green on `gradlew.bat assembleDebug testDebugUnitTest lintDebug` (rerun from scratch, 3 Nov):
  - **Step 1, backup and restore** (Settings → Data). One `.json.gz` file: a header line, then one location per line. Restore is replace-only, in one transaction, over two passes, so memory stays flat (≤ 4.3 MB at 100k locations). Spec: `docs/specs/phase-7.md` § 1. The owner backed up and restored on the phone on 3 Nov: OK.
  - **Step 2, editable Billing settings.** Rounding Off/6/15/30, Round to Nearest/Always up, Minimum Off/15/30/60, and "Round per day, per job". § 2.
  - **Step 3, Summary tab.** The bar is Timeline · Summary · Settings · tracking toggle. The pure `SummaryCalculator` has golden tests. § 3. The owner likes it; small UI/UX tweaks go to phase 7.5.
  - The reviewer subagent passed steps 1–3 ("approve with fixes"), and all its fixes are in (a7f0cf4, 81abd5a, 98c0d85).
- **On the phone:** this branch's build has been installed since 3 Nov 17:06. Pre-install backup: `tmp/pre-phase-7-install/` (DB plus datastores). The real DB copy for tests is the session scratchpad `realdb/tarry.db` (v2); it is session-scoped, so pull a fresh one if it is gone.
- **Phase 6.5 leftovers:**
  - The time-zone fix passed on the phone on 3 Nov: a stop on Perth time re-bound to 07:30 Sydney.
  - The battery baseline (an untracked day off, e.g. Sat 7 Nov) is still to measure and add to `docs/specs/phase-6.5.md` § 3.
- **UI-reviewer screenshots** of the bar, Summary and Billing were taken on 3 Nov. Their findings belong to the 7.5 list unless something is a blocker; see the session notes or re-run `ui-reviewer`.

## Owner decisions so far (phase 7)
- **Backup:** pings are always included, as one `.json.gz`, through SAF. Restore is replace-only. Overrides active at the backup's `createdAt` end at it. Pings are streamed (never all in memory).
- **Billing:**
  - one switch applies rounding **and** the minimum either per block (off, the default) or to each job's daily total (on);
  - minimum presets are Off/15/30/60;
  - billing counts **whole minutes**: seconds are dropped before rounding, and the minimum still applies when raw time is above 0.
- **Summary:**
  - the grand total reads "Billed X · on the clock Y" (Y is the union);
  - Unlabelled is uncovered visits at non-ignored places and trips ("On site" / "Travel");
  - hours only, with no rates and no schema change;
  - a block crossing midnight is split into day pieces;
  - blocks of deleted jobs stay excluded from coverage (owner: leave as is for now).

## Goal (steps 4 and 5)
Get the Summary's numbers out of the app:
- **CSV** (step 4), a stopgap for Walkflow, so it carries stable IDs;
- **PDF** (step 5), one page per job.

Both are user-initiated SAF exports of the Summary's current range and grouping, from an export button on the Summary screen. Keep the export code free of entitlement checks: phase 8 wraps gating in one place.

## Ask the owner first (rule 12, don't guess)
1. **CSV shape for Walkflow.**
   - Proposal: one row per job block piece, with `job_block_id`, `job_id`, `job_name`, `date`, local `start` and `end` with an ISO offset, `duration_min`, `billable`, `billed_min`, and `notes`.
   - Should segment rows (visits and trips, with the ids of the blocks covering them) go in the same file, a second file, or not at all?
   - Does Walkflow need a specific column order or header names? Ask whether the owner has a sample import.
2. **Clipping:**
   - Proposal: an export clips a block that crosses the range edge (matching the Summary).
   - In per-day mode, should `billed_min` appear per block or only on a per-day total row?
3. **PDF:**
   - one page per job: header (job, range, totals), then a blocks table with notes;
   - should covered visits (place names) be included;
   - is a cover/total page wanted;
   - paper size (A4).
4. **Filenames:** e.g. `tarry-summary-<from>_<to>.csv`, and whether there is one file per export or a zip.

Then follow the `plan-feature` skill: add §4 and §5 to `docs/specs/phase-7.md`, get approval, implement with `implementer`, review with `reviewer` (CSV/PDF are money paths), then `ui-reviewer`.

## Constraints
- **Rule 2:** no network, and files leave only to a location the user picks through SAF. Use Android `PdfDocument` (no new dependency). The pattern for SAF writing is `app/.../export/RawExporter.kt`.
- **Rule 7:** tests are needed for:
  - CSV golden output (escaping of commas, quotes and newlines in notes and job names; offsets on DST days; clipping);
  - CSV header stability;
  - PDF page count and text content where practical;
  - Compose tests for the export button and its states.
- **Fixtures are synthetic.** Real exports and DB pulls stay in `tmp/`.
- **Device safety:** back up the phone DB to `tmp/` before any install, install only when the owner is idle (an install mid-window kills the session, which is recorded as a short Gap and auto-restarts), and ask before a reboot or force-stop.
- **Windows:** use `gradlew.bat`, prefix adb commands with `MSYS_NO_PATHCONV=1`, use Edit rather than `perl -0pi` (CRLF), never use `git commit -a`, and never commit `.idea/`.

## Leftover review nits (fix only if touching the file; otherwise 7.5)
- `BackupManager.backUp`: the body of the `.use { writer -> … }` block is not re-indented.
- `ShellComponentsTest` and others assume fixed slot arithmetic for the bottom bar.
- Unrelated warnings: the deprecated-field warnings in `AndroidScheduleAlarmSchedulerTest`, a deprecated Locale constructor in `WeekStripTest`, and the manifest-merger WorkManagerInitializer warning.

## Definition of done (phase 7)
- [ ] Steps 4 and 5 are specced, approved, built and reviewed.
- [ ] `gradlew.bat assembleDebug testDebugUnitTest lintDebug` is green, plus the opt-in real-DB backup tests (`TARRY_REAL_DB=<v2 copy>`, `*RealDbBackup*`; don't run `RealDbMigrationTest` with a v2 DB).
- [ ] The owner has tried CSV and PDF export on the phone.
- [ ] Push the branch, open a PR to `main` (the body ends with the Claude Code line), and the owner merges.

## Verification commands
```
gradlew.bat :app:testDebugUnitTest :core-data:testDebugUnitTest
gradlew.bat assembleDebug lintDebug
set TARRY_REAL_DB=<v2 DB copy> && gradlew.bat :core-data:testDebugUnitTest --tests "*RealDbBackup*" :app:testDebugUnitTest --tests "*RealDbBackupFileTest"
```
