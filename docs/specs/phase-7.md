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
     - every manual override (START or PAUSE) still active at the backup's `createdAt` is ended at `createdAt` in the same transaction as the inserts, so the restored phone follows only the schedule until the user acts; overrides that had already ended are untouched (owner decision 2026-11-02);
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

### Streaming locations (owner decision, 2026-11-03)
Locations are ~99% of the rows (about 100k at 90 days). They are never all in memory, on backup or restore. Everything else (sessions, visits, trips, places, jobs, blocks, gaps, schedule, overrides, settings) stays small and is held in memory as now.

- **File body:** the gzip stream holds a sequence of newline-separated JSON values, not one object:
  1. the header, i.e. today's `BackupFile` with `tables.pings` empty;
  2. one `PingDto` per line, in ascending id order.
  
  A file whose header still has inline `tables.pings` (the earlier single-object layout) decodes too, so `formatVersion` stays 1; nothing was released with the old layout. Decoding uses `Json.decodeToSequence` over `JsonElement`s: the first must be the header, every later one a `PingDto`. Truncation is caught by the gzip trailer (EOF or CRC error → DAMAGED).
- **Back up:**
  - one short transaction reads the small tables and `maxPingId`;
  - pings are then read by keyset pages (`id > after AND id <= maxPingId ORDER BY id LIMIT page`) outside a transaction, so tracking writes are never blocked while the file goes to a slow provider;
  - the 90-day purge running mid-backup only drops leaf pings, so the file stays referentially valid;
  - the snackbar count is the number of pings actually written.
- **Restore, two passes over the file:**
  1. **preview:** stream the whole file once; validate the header and small tables as now, and every ping (its session exists, it converts to an entity, and its id is greater than the previous streamed ping's, which also makes ids unique with no set of all ids; ids of inline pings from the earlier layout are held in a set instead, and a streamed id may not repeat one); keep only the count and the small tables.
  2. **apply:** inside the one Room transaction, delete everything, insert the small tables, then stream the file again and insert pings in chunks of 500. The second pass runs the same ping checks with fresh state. If a check fails, the second pass's ping count differs from the preview's, or anything throws, the transaction rolls back and the restore fails with nothing changed (the file was changed or became unreadable between passes).
  - Open sessions from the backup are closed after the pings are in, with one SQL update (`end` = the session's last ping, or its start), `KILLED`, no Gap.
- **Memory bound:** at most one page or chunk of pings (500–1,000) plus the small tables is held at a time.

### Risks
- **Size and memory:** about 100k pings. Handled by streaming (above). Verified with a synthetic 100k-ping round trip and the real-data test.
- **DataStore and Room are not one transaction:** handled by snapshot and rollback. A crash at exactly that point could leave the new settings with the old DB, which is harmless; nothing is lost from the DB.
- **Restoring during a scheduled window** stops and restarts tracking under the controller lock. No Gap row is written for that stop and start; a failed restore undoes the stop. The restore runs to completion even if the user leaves the screen.

## 2. Editable Billing settings

### Problem
Settings → Billing shows rounding increment, rounding mode, minimum billable time and "Round daily total per job" as read-only values. The Summary (step 3) bills with them, so the user must be able to set them. The DataStore keys and `SettingsRepository` setters already exist.

### Owner decisions (2026-11-03)
- **One switch decides where rounding and the minimum apply.**
  - Off (default): each job block is rounded and topped up to the minimum on its own.
  - On: a job's blocks on one local day are added up first, then rounded and topped up once.
  - It keeps the existing key `round_daily_total_per_job`, relabelled "Round per day, per job".
- **Minimum billable offers Off, 15, 30 and 60 min**, through a picker like the stop radius.

### UI
All rows are in Settings → Billing and use the existing components (`SettingsValueRow`, `SettingsSwitchRow`, `PickerSheet`).

| Row | Control | Options or behaviour |
|---|---|---|
| Rounding | picker | Off, 6 min, 15 min, 30 min (`Settings.ALLOWED_ROUNDING_INCREMENTS`) |
| Round to | picker | "Nearest" or "Always up". Disabled, with the subtitle "Rounding is off", while rounding is Off. |
| Minimum billable time | picker | Off, 15 min, 30 min, 60 min (new `Settings.ALLOWED_MIN_BILLABLE_MINUTES`). A stored value outside the list (none exist today) still shows as "N min" and is never rewritten unless the user picks. |
| Round per day, per job | switch | Subtitle: "Add up each job's blocks for the day before rounding and the minimum." |

- Each picker sheet has a one-line explanation. Rounding's is "Billed time is rounded to this step", and the mode sheet explains nearest against always up.
- Labels are readable by TalkBack (no enum names shown), rows are at least 48 dp, and tokens come from the theme.
- The other Billing rows (location retention, review reminder) are unchanged and stay display-only.

### Data
No Room change. No new DataStore keys. Backups already carry these keys.

### Tasks
| # | Task | Size |
|---|---|---|
| S1 | `Settings.ALLOWED_MIN_BILLABLE_MINUTES`; view model setters for the four settings | S |
| S2 | Billing rows: three pickers and a switch, strings, the disabled mode row | S |
| S3 | Tests (below) | S |

### Acceptance criteria
- Compose (`SettingsContent`):
  - each picker row opens its sheet with the right options and current choice;
  - picking calls the callback with the value;
  - the mode row is disabled while rounding is Off and enabled otherwise;
  - the switch calls its callback;
  - the labels contain no enum names.
- View model: each setter writes through `SettingsRepository`, and the settings flow reflects it.
- Commands: `gradlew.bat :app:testDebugUnitTest :core-data:testDebugUnitTest`, `gradlew.bat assembleDebug lintDebug`.

## 3. Summary

### Problem
The timeline shows one day of job blocks, but invoicing needs totals over a range: per job, with rounding and the minimum applied, and with uncovered time visible so nothing is lost.

### Owner decisions (2026-11-03)
- **Grand total:** per-job totals, then "Billed 9 h 30 · on the clock 7 h". "Billed" sums every job. "On the clock" is the wall-clock time any block covers, so overlapping blocks count once there.
- **Unlabelled:** visits at non-ignored places and trips that no block covers, shown as two lines, "On site" and "Travel".
- **Where:** a bottom-bar tab. The bar becomes **Timeline · Summary · Settings · tracking toggle**, with the toggle moving from the centre to the right end.
- **Hours only.** No rates, so no schema change.
- From step 2: one switch applies rounding and the minimum either per block or per job per day.
- **Whole minutes (2026-11-03):** billing counts whole minutes; seconds are dropped before rounding and the minimum.

### Calculation (pure Kotlin in `app/.../summary/`, JVM-tested)
**Inputs:**
- the range: inclusive local dates `from..to` and the zone;
- live (not deleted) job blocks overlapping the range, and their jobs;
- visits and trips in the range;
- the ignored places;
- the billing settings.

**Steps:**
1. **Clip and split.**
   - Each block is clipped to `[startOfDay(from), startOfDay(to + 1))` in the zone.
   - It is then split at each local midnight into day pieces. Day boundaries come from `ZonedDateTime`, so DST days are 23 h or 25 h.
   - A block of a deleted job is excluded. Archived jobs are included.
2. **Billed time** (billable pieces only; non-billable pieces count toward total time, never toward billed):
   - per block (switch off): each billable day piece is billed on its own;
   - per day, per job (switch on): a job's billable pieces on one local day are added up and billed once.
   
   Each billing unit is rounded first, then the minimum is applied:
   - **Whole minutes** (owner decision 2026-11-03): the unit's raw time is first truncated down to whole minutes, dropping the seconds. In per-day mode the pieces' raw times are added up before truncating (2 x 30 min 40 s = 61 min 20 s bills 61 min). Raw total, billable and on-the-clock figures stay exact.
   - **Rounding** to the increment (of the truncated time): NEAREST rounds half up (8 min at a 15 min step becomes 15; 7 min 30 s is 7 min first, so 0), UP rounds up any part of a step. An increment of 0 means no rounding.
   - **Minimum:** `billed = max(rounded, minimum)` when the unit's raw time is above 0. This applies even if truncation or NEAREST left it at 0 (a 40 s unit with a 15 min minimum bills 15 min).
3. **Groups.**
   - **By job** (default): one row per job with blocks in the range, showing block count, total time (raw, clipped), billable raw time and billed time. Rows are sorted by billed time, descending.
   - **By day:** one section per local day with that day's job rows.
4. **Grand totals.**
   - **Billed:** the sum of every row.
   - **On the clock:** the length of the union of all block pieces, of any job and any billable flag, in the range.
5. **Unlabelled.**
   - **On site:** time of non-deleted visits at non-ignored places, clipped to the range, that no live block covers. Only the uncovered part counts.
   - **Travel:** the same for trips.

**Assumption (cheap to change):** a block crossing local midnight is billed as two day pieces in both modes, so the by-day view and the per-day mode agree. Overnight blocks are rare.

### UI
- **Bottom bar:** add the Summary slot (new icon and the label "Summary"). Order: Timeline, Summary, Settings, then the toggle. The selection indicator glides between the three nav items. Bar tests are updated.
- **Summary screen:**
  - **Range chips:** Today, Yesterday, This week, Last week, Custom. Weeks run Monday to Sunday, as in the schedule. Custom opens a Material date-range picker. The chosen range survives rotation.
  - **Grouping:** a "By job / By day" segmented toggle.
  - **Job rows:** colour dot, name ("archived" suffix if archived), block count, total, and billed in bold.
  - **Footer cards:**
    - "Billed X · on the clock Y";
    - "Unlabelled: on site A · travel B", hidden when both are 0.
  - **Tapping a job row** opens that job's blocks in the range: date, start–end, duration, billed, and notes. Tapping a block opens the timeline at that day.
  - **Empty state:** "No job blocks in this range." The unlabelled card still shows, so untagged time is visible.
  - **Live data:** the screen follows Room flows, so editing a block on the timeline updates the Summary.
  - **No export button yet.** Step 4 adds it.
- Tokens, touch targets of at least 48 dp, TalkBack labels, and durations read as "2 hours 15 minutes".

### Tasks
| # | Task | Size |
|---|---|---|
| U1 | `SummaryCalculator` (pure) and golden tests | M |
| U2 | `SummaryRepository`/view model: range → flows (blocks, jobs, visits, trips, places, settings) → calculator; range state | M |
| U3 | Bottom bar: Summary slot, toggle on the right, route, bar tests | S |
| U4 | Summary screen, the job-blocks drill-down, navigation to the timeline day, Compose tests | M |

### Acceptance criteria
- Golden tests (U1):
  - rounding NEAREST and UP at 6, 15 and 30 min, plus off;
  - the minimum with and without rounding;
  - per-block against per-day mode for the same blocks;
  - non-billable blocks;
  - clipping at range edges and at midnight;
  - Sydney DST start and end days;
  - two jobs overlapping (billed counts both, on the clock counts once);
  - an archived job is included;
  - a deleted block and a deleted job are excluded;
  - unlabelled on site and travel with partial coverage;
  - an ignored place is excluded.
- View model: changing the range or grouping recomputes, and a block edit re-emits.
- Compose:
  - the bar order and selection;
  - the range chips;
  - rows show the right values;
  - drill-down lists blocks, and a block tap navigates to its day;
  - the empty state;
  - the unlabelled card hides at zero.
- Commands: `gradlew.bat :app:testDebugUnitTest`, `gradlew.bat assembleDebug lintDebug`.
