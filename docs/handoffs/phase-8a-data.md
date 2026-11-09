# Handoff: phase 8a.1 — Data (retention that prunes, delete all data)

**From:** Claude (planner, `phase-8`) → **To:** implementer · **Date:** 2026-11-09 · **Branch:** `feat/phase-8-launch` (already checked out; commit there, never main).

Read first: `AGENTS.md`, `docs/design/ui-rules.md` (§2 writing, §3 confirmations, §7 checklist), `docs/handoffs/phase-8-launch.md` § "Owner decisions, 2026-11-09".

## 1. Raw-ping retention that actually prunes
**Today:** Settings → Data → "Raw location retention" is display-only. `SettingsRepository` (`core-data/.../settings/SettingsRepository.kt`) already stores `raw_ping_retention_days` / `raw_ping_retention_forever`, with the setter `setRawPingRetentionDays(Int?)` (null = forever), default 90, `ALLOWED_RETENTION_DAYS = {30, 90, 365}` in `Settings.kt`. `LocationPingDao.deleteOlderThan(before)` exists (`core-data/.../dao/TrackingDaos.kt`) and is unused.

**Build:**
- A pure, injectable use case (e.g. `PingRetention` in `app/.../segmentation/` or a new `app/.../data/` package, your call, match nearby style) that computes the cutoff and calls `deleteOlderThan`:
  - forever → no-op;
  - cutoff = `now - days`, then clamped so it never reaches into **the open tracking session** (`TrackingSessionDao.getOpenSession()`: cutoff ≤ its start) or into **the window segmentation may still re-run over**. Read `app/.../segmentation/SegmentationUpgrade.kt` and `SegmentationRunner.kt` to find that window (checkpoint and any retention-based re-run window) and clamp to the earliest of these.
  - Clock injected (use the project's existing clock abstraction if there is one).
- A `RetentionWorker` (Hilt `@HiltWorker`, follow `HealthCheckWorker` for the pattern) that runs the use case. Enqueue it as unique periodic work, once a day, `ExistingPeriodicWorkPolicy.KEEP`, from wherever `HealthCheckWorker` is enqueued (likely `TarryApplication`). No constraints. Rule 5: WorkManager only, no own timers. Any period/tunable goes in `TrackingConfig` (or an existing config object), not a literal.
- Settings UI: make the retention row tappable and open the existing picker pattern used by Stop radius / Window-end grace (same sheet/dialog component). Options: "30 days", "90 days", "1 year", "Forever". Row hint/subtitle per ui-rules §2 sentence style: "Older days keep their visits and trips. Their route lines on the map are removed." Strings in `strings.xml`.
- Tests: JVM tests for the use case with a fake DAO/clock: normal cutoff, forever no-op, clamp by open session, clamp by segmentation window. A Robolectric/Compose test that the row opens the picker and selecting calls the setter (follow `SettingsBillingUiTest` style).

## 2. Delete all data
**Build:**
- In `core-data`: a `deleteAll()` on `BackupStore` (or `BackupDao`) in **one transaction**, clearing every Room table: visits, trips, places, jobs, job blocks, pings, sessions, manual overrides, segmentation checkpoint, and any other entity tables in `TarryDatabase`. Reuse the existing table-clearing queries that restore uses in `BackupDao` (`deletePings()` and siblings), don't duplicate them. **No schema change.** If you find you need one, stop and report.
- **Keep:** all DataStore settings (schedule, theme, rounding, retention, etc.). Only clear `last_backup_ms` if it lives with backup prefs and makes the Data section misleading; otherwise leave it.
- In `app`: before deleting, stop tracking and close any open session through the same hooks restore uses (`TrackingRestoreHooks` in `app/.../export/backup/BackupManager.kt`), and afterwards re-run whatever restore runs after it (reconcile / reschedule) so tracking resumes per schedule. Put the orchestration next to restore in `DataBackup`/`BackupManager` or a small sibling class.
- Settings UI: at the end of Settings → Data, a `DestructiveButton` "Delete all data". It opens an `AlertDialog`:
  - title "Delete all data";
  - body: "This removes every visit, trip, place, job and location point from this phone. Settings and your schedule stay. Back up first if you might want them. This can't be undone.";
  - an `OutlinedTextField` labelled "Type delete to confirm";
  - confirm `DestructiveButton` "Delete all data", enabled only when the trimmed, case-insensitive text equals "delete"; `TertiaryButton` "Cancel".
  - Busy state reuses the Data section's existing progress row; afterwards a snackbar "All data deleted" (no Undo; it's confirmed). On failure, an inline error per ui-rules §2.
- Tests: a Room/Robolectric test (follow existing `BackupDao`/store tests) that after `deleteAll()` every table is empty and a settings value survives; a test that the hooks are called in order (stop → delete → resume); a Compose test that the confirm button is disabled until "delete" is typed, works at font scale 2.0 without clipping.

## Constraints
- Small diffs, no drive-by refactors (list anything else you notice).
- Strings in `strings.xml`, tokens only, 48 dp targets, one TalkBack node per row.
- Run `gradlew.bat testDebugUnitTest lintDebug assembleDebug` (from the repo root) until green.
- Commit in two commits (retention; delete-all), staging explicit paths only. **Never** stage `.idea/` or use `commit -a`. End each commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Don't install on the phone and don't run `connectedDebugAndroidTest`.
- Report: files changed, test results, anything ambiguous you decided, anything you skipped.
