# Phase 7: Summary and export

Handoff: `docs/handoffs/phase-7-summary-export.md` (latest on the `docs/handoffs-6.5-to-8` branch until PR #26 merges).

**Build order (owner, 2026-10-31):**
1. backup and restore;
2. editable Billing settings;
3. Summary;
4. CSV;
5. PDF.

This spec grows one section per step.

## 1. Backup and restore

### Problem
The dogfood data is protected only by `adb` pulls. A lost or reset phone loses every labelled day. Before the app's numbers are used for invoicing, the owner needs a complete, user-initiated backup and a restore that cannot half-apply.

### Owner decisions (2026-11-02)
- **Always include raw pings.** The backup is one gzip-compressed JSON file (`.json.gz`).
- **Restore is replace-only** and runs in one transaction. Merge waits for sync.
- **It lives in Settings → Data:** "Back up now" and "Restore from backup", both through the system file picker (SAF).

### Goals and non-goals
- **Goals:**
  - a complete copy of the user's data;
  - a restore that leaves either the old data or the backup's data, never a mix;
  - backups from an older format version still restore.
- **Non-goals:**
  - merge;
  - automatic or scheduled backups (they would need a standing destination; rule 2 also forbids cloud);
  - encryption (the file goes where the user puts it; see Privacy);
  - restoring onto a different app or schema that is newer than the app.

### What is in a backup
| Part | Included | Why |
|---|---|---|
| Room: every table (sessions, pings, places, visits, trips, jobs, job blocks, gaps, excluded ranges, schedule template and overrides, manual overrides, segmentation checkpoint) | yes | A full device copy. The checkpoint matches the restored visits and trips. |
| Settings DataStore (`settings`), all keys, including `segmentation_version` | yes | User preferences. The version makes `SegmentationUpgrade` re-run if the backup is older than the algorithm. |
| Declined suggestions DataStore | yes | These are user decisions. |
| `tracking_status` DataStore (heartbeat, attention, open Gap id, schedule edit history, AR log) | **no** | Local control state for this device and this process. |

### File format
`tarry-backup-<yyyy-MM-dd>.json.gz` holds one JSON object:
- `format: "tarry-backup"`, `formatVersion: 1`;
- `createdAt` (UTC ms), `zoneId`, `appVersionName`;
- `settings` (typed key/value list), `declinedSuggestions`;
- `tables`: one array per table, made of `@Serializable` DTOs that mirror the entities field by field.

DTOs live in `:app` (`export/backup/`). `:core-data` stays free of serialization.

- **Older backups** decode because new DTO fields always get defaults. A breaking change bumps `formatVersion` and adds an explicit `upgrade(vN → vN+1)` function, with a test.
- **A newer `formatVersion`** than the app knows is rejected with a clear message.
- Encode and decode stream through `GZIPOutputStream` and `GZIPInputStream`. No whole-file string is built (about 100k pings at the 90-day retention).

### Flows
- **Back up:**
  1. Settings → Data → "Back up now" opens SAF `CreateDocument("application/gzip")` with the suggested name;
  2. the backup is written on IO with a progress indicator;
  3. a snackbar shows "Backed up 77 visits, 10,952 locations" or the error.
  - Reads take one consistent snapshot inside a read transaction.
- **Restore:**
  1. "Restore from backup" opens SAF `OpenDocument`;
  2. the whole file is decoded and validated **before anything changes** (format, version, referential checks such as every ping's session existing). An invalid file gets an error and no change;
  3. a confirm dialog shows the backup's date and counts against the current counts: "This replaces everything on this phone." Its buttons are **Back up current data first** (runs the backup flow, then returns), **Replace** and **Cancel**;
  4. tracking is stopped: an open session ends `USER_STOP` and geofence and AR are torn down;
  5. **apply:**
     - snapshot the two DataStores;
     - write the backup's DataStore values;
     - then one Room `withTransaction` deletes every table and inserts the backup (sessions before pings, places and jobs before visits and blocks);
     - if the transaction throws, the DataStore snapshots are written back and an error is shown. Room has rolled back itself;
  6. **after:**
     - an open session in the backup (a backup taken mid-session) is closed at its last ping with `KILLED` and **no** Gap. The time between backup and restore was not lost on this device, so recovery must not write one;
     - then the controller reconciles (a window in progress starts a fresh session), the segmentation upgrade check runs, and the UI follows Room invalidation.

### UI states
- **Settings → Data:**
  - "Back up now" (subtitle "Last backup: <date>" once one exists, kept in the settings store);
  - "Restore from backup".
  - The rows stay disabled while an operation runs, with a progress row.
- **Errors**, each a snackbar with no data change: cannot open the file; not a Tarry backup; made by a newer version of Tarry; damaged file.
- Rows are at least 48 dp, labelled for TalkBack, and use tokens.

### Privacy (rule 2)
- No network.
- The file leaves only to a location the user picks.
- `allowBackup=false` stays.
- The file is not encrypted, and the Data section says so in one line: "The file contains your full location history. Keep it somewhere private."

### Data model
No Room schema change (stays at v2). One new settings key: `last_backup_ms`.

### Tasks
| # | Task | Size | Executor |
|---|---|---|---|
| B1 | `BackupDao` in `:core-data`: `SELECT *` per table, `@Insert` lists per entity, `DELETE` per table; `BackupStore.snapshot()` (read transaction) and `replaceAll(snapshot)` (one `withTransaction`, FK order); closes open sessions without a Gap | M | claude |
| B2 | DTOs + `BackupCodec` (gzip JSON stream encode/decode, `formatVersion`, validation); settings and declined-suggestions snapshot/restore | M | claude |
| B3 | `BackupManager` in `:app`: export to `Uri`; import = decode → validate → stop tracking → DataStore write → `replaceAll` → rollback on failure → reconcile + segmentation upgrade check | M | claude |
| B4 | Settings → Data section, SAF launchers, confirm dialog with counts and "Back up current data first", progress/snackbars, strings | S | cursor |
| B5 | Tests (below) | M | claude |

### Acceptance criteria
- A backup of a DB with every entity type, decoded, equals the snapshot it came from (JVM round trip, all fields, including nulls and enums).
- Restore into a DB holding different data gives exactly the backup's rows. The old rows are gone.
- A restore that fails mid-insert (an injected bad row) leaves the original DB **and** settings unchanged.
- A newer `formatVersion` is rejected and a non-gzip or non-Tarry file is rejected, with nothing changed.
- A `formatVersion: 1` fixture missing a later optional field still decodes (guards future versions).
- An open session in the backup is closed at its last ping with `KILLED`, and no Gap is written for backup-to-restore time.
- A real-data check (skipped unless `TARRY_REAL_DB` is set, `RealDbMigrationTest` pattern): back up a copy of the owner's DB, restore it into an empty DB, and every table count and row matches. The file stays in `tmp/`.
- Compose: the Data rows exist with labels; Restore shows the confirm dialog with counts; Cancel changes nothing.
- Commands:
  - `gradlew.bat :core-data:testDebugUnitTest :app:testDebugUnitTest`
  - `gradlew.bat assembleDebug lintDebug`
  - real data: `set TARRY_REAL_DB=<copy> && gradlew.bat :core-data:testDebugUnitTest --tests *RealDbBackup*`

### Risks
- **Size and memory:** about 100k pings. Stream the encode and decode, and insert in chunks inside the transaction. Measure with the real-data test.
- **DataStore and Room are not one transaction:** handled by snapshot and rollback. A crash at exactly that point could leave the new settings with the old DB, which is harmless; nothing is lost from the DB.
- **Restoring during a scheduled window** stops and restarts tracking, which leaves a short Gap of a few seconds. That is acceptable and visible.
