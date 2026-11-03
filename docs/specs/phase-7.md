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

## 4. CSV export

### Problem
Walkflow invoices from Tarry's hours, but the numbers are trapped on the Summary screen. Until there is a real integration, a CSV with stable ids is the stopgap.

### Owner decisions (2026-11-03)
- **Blocks only.** No visit or trip rows.
- **Per-day mode:** block rows leave `billed_min` empty, and one `day_total` row per job per day carries it.
- **Clipping:** like the Summary. A block crossing the range edge is clipped, and the row says so (`clipped`).
- **Headers:** our snake_case names in a fixed order. The header row is frozen by a test.
- **File:** one file, `tarry-summary-<from>_<to>.csv` (ISO dates, both always present), saved through SAF.

### Format
- RFC 4180: UTF-8 without a BOM, CRLF line ends, and a field is quoted only when it holds a comma, quote, CR or LF (quotes doubled). This matches `PingCsvFormat` in `export/RawPingFormats.kt`.
- The output is locale-independent: ASCII digits, `true`/`false`, and no thousands separators.
- **Header (frozen):** `row_type,job_block_id,job_id,job_name,date,start,end,duration_min,billable,billed_min,clipped,notes,place_names,place_addresses,place_coords` (the last three were added 2026-11-04, appended at the end so existing columns keep their positions)

| Column | `block` row | `day_total` row (per-day mode only) |
|---|---|---|
| `row_type` | `block` | `day_total` |
| `job_block_id` | the block's UUID; a block split at midnight gives two rows with the same id | empty |
| `job_id`, `job_name` | the job's UUID and current name | same |
| `date` | the local date of the piece, `2026-11-03` | the day |
| `start`, `end` | the piece's local time with offset, `2026-11-03T07:30:00+11:00` (seconds, no millis) | empty |
| `duration_min` | the piece's raw clipped time in whole minutes, truncated (the basis billing uses) | the job's billable raw time that day, truncated |
| `billable` | `true`/`false` | empty |
| `billed_min` | per-block mode: billed minutes (`0` when not billable); per-day mode: empty | the day's billed minutes |
| `clipped` | `true` when the range edge cut the piece (a midnight split alone does not count) | empty |
| `notes` | the block's notes, as typed, except a note starting with `=`, `+`, `-` or `@` gets a leading `'` (formula guard, owner 2026-11-04) | empty |
| `place_names` | the places visited during the piece (same list as the PDF's Places, from `coveredPlaces`: time order, no repeats, ignored places left out), joined with `; `. A place with no name uses its address, then "Unnamed place". Formula guard as for notes | empty |
| `place_addresses` | the same places' addresses, joined with `; `, one entry per place in the same order; a place without an address gives an empty entry. Formula guard | empty |
| `place_coords` | the same places' centroids as `lat,lng` with 4 decimals (about 11 m), `Locale.ROOT`, joined with `; `. Never raw pings | empty |

**Why location (owner, 2026-11-04):** Walkflow will match a block to its own project by time overlap **and** location. Walkflow stores the coordinates where a project's videos and snapshots were taken, so `place_coords` is the main location key.

- **Order:** by date, then by start, then by job name; in per-day mode each `day_total` row follows its job's last block row that day.
- **Offsets** come from the Summary's zone at each instant, so DST days carry the right offset on each side of the change.
- **Totals** are not written as rows. In per-block mode `billed_min` over the block rows adds up to the Summary's Billed; in per-day mode the `day_total` rows do. That is a test.

### Flow and UI (shared with § 5)
- **Export button:** an icon button ("Export", 48 dp) at the right of the Summary title opens a menu: "CSV (spreadsheet)" and "PDF (report)".
- **Enabled** only when the range has job blocks (`result.hasBlocks`). The button stays in place when disabled, with the content description "Export, no job blocks in this range".
- **On a choice,** SAF `CreateDocument` opens with the suggested filename. When the user cancels, nothing happens.
- **Writing:**
  - runs on `Dispatchers.IO` from the current result for that range and grouping;
  - while it runs, the button shows a progress indicator and is disabled;
  - on success, a snackbar says "Saved 14 blocks to CSV" (or "to PDF");
  - on failure, the partial file is deleted (as `BackupManager` does) and a snackbar says "Couldn't save the export. Try again."
- **Snackbars** go through the app Scaffold's `SnackbarHostState` via a view model event Channel, the pattern Settings uses.
- **No entitlement checks** here: phase 8 gates exports in one place.

### Privacy (rule 2)
No network. The file goes only to the location the user picks. Since 2026-11-04 the CSV holds place names, addresses and place centroids, so a shared file reveals client sites. Raw pings are never included. An "include locations" switch can be added if the CSV is ever given to anyone but Walkflow.

### Tasks
| # | Task | Size | Executor |
|---|---|---|---|
| C1 | `SummaryCsv` (pure, `app/.../export/summary/`): `SummaryResult` + zone + rules → rows → `Appendable`. Golden tests. | M | implementer |
| C2 | `SummaryExporter`: SAF write on IO, partial-file delete, typed failure; `SummaryViewModel.export(format, uri)` and an event Channel | M | implementer |
| C3 | Export button and menu, the `CreateDocument` launcher, progress/disabled states, snackbar wiring in `TarryApp`; Compose tests | S | implementer |

### Acceptance criteria
- Golden CSV tests (C1) on synthetic data:
  - the header line is exactly the frozen string;
  - escaping: commas, double quotes, CR and LF in notes and job names;
  - offsets on the Sydney DST start and end days;
  - clipping at both range edges (`clipped=true`) and a midnight split (`clipped=false`, same id on two rows);
  - per-block mode: `billed_min` sums to `SummaryResult.billedMs`;
  - per-day mode: block rows have empty `billed_min`, and the `day_total` rows sum to `billedMs`;
  - a non-billable block bills `0` in per-block mode and is left out of `day_total`;
  - the row order.
- Exporter (C2): it writes through a fake `ContentResolver` stream; a write failure deletes the partial file and reports a failure event.
- Compose (C3): the button is disabled with no blocks; the menu shows both formats; the exporting state shows progress.
- Commands: `gradlew.bat :app:testDebugUnitTest`, `gradlew.bat assembleDebug lintDebug`.

## 5. PDF export

### Problem
A client or an accountant wants a readable hours report, not a spreadsheet.

### Owner decisions (2026-11-03)
- A4, one file `tarry-summary-<from>_<to>.pdf`, saved through SAF from the same Export menu (§ 4).
- A **cover page** with the totals, then the jobs. **Revised 2026-11-04** after the phone review: jobs flow one after another instead of one page each. Each job section starts on the cover page below the totals, or below the previous job, and a page breaks only when it fills. A job header never sits alone at a page bottom: it needs room for itself, the table header and its first row.
- **Revised 2026-11-04:** text sizes are body 10.5 pt, the totals line 13 pt, the title 18 pt and the footer 8 pt.
- **Billed versus total stays as it is (owner 2026-11-04).** "Billed 55 min · total 56 min" is correct: billing drops seconds per unit, and the total is exact.
- Job pages list the **places visited** during each block.
- **Unlabelled time** (on site, travel) is included.

### Pages
- **A4 portrait** is 595 × 842 pt with 40 pt margins. It uses the system sans font (no bundled font, no new dependency). Text is black on white, whatever the app theme.
- **Footer** on every page: "Tarry · <from> – <to> · Page n of m".
- **Cover:**
  - title "Hours summary" and the range, e.g. "Mon 2 Nov – Sun 8 Nov 2026";
  - the billing rules in words, e.g. "Rounded to the nearest 15 min, minimum 30 min, per day per job";
  - a table with one row per job: name (plus "(archived)"), blocks, total, billed;
  - "Billed X · on the clock Y";
  - "Unlabelled: on site A · travel B", left out when both are 0.
- **Job section** (jobs in the cover's order; it flows, as revised above):
  - header with the job name, the range, and "Billed X · total Y · billable Z";
  - a table with the columns Date · Time · Duration · Billed · Places · Notes. Time is local "7:30 am – 12:05 pm", as on the Summary, and Billed is empty in per-day mode;
  - per-day mode: a "Day total, billed X" line after each day's rows;
  - Places and Notes wrap within their columns, and a row never splits across pages. A row taller than a page is truncated with "…";
  - the next page repeats the header line as "<Job> (continued)" and the table header.
- **Durations** read like the app: "2 h 15 min".
- **The grouping toggle does not change the PDF.** By job is its natural shape, and the cover already gives per-job totals. (Assumption; a by-day variant is cheap to add later.)

### Places visited
- New pure function `coveredPlaces(piece, visits, places)`: the names of non-deleted visits that overlap the piece, in time order, without duplicates.
- Visits at ignored places are left out.
- The name is the place name, then its address, then "Unnamed place". A visit with no place is skipped.
- This feeds only the PDF. The calculator and the CSV stay as they are.

### Design
- **Layout is separate from drawing.** `SummaryPdfLayout` (pure Kotlin) turns the result into a list of pages, each holding positioned text runs and rules. It measures text through a small `TextMeasurer` interface, so tests run on the JVM with a fixed-width fake.
- `SummaryPdfRenderer` draws those pages with `android.graphics.pdf.PdfDocument`, using `Paint`/`StaticLayout` behind `TextMeasurer`, and writes to the SAF stream. It holds no logic of its own.

### Tasks
| # | Task | Size | Executor |
|---|---|---|---|
| P1 | `coveredPlaces` and tests (overlap, ignored, fallback names, dedupe) | S | implementer |
| P2 | `SummaryPdfLayout` + `TextMeasurer` and JVM tests: page count, cover contents, continuation pages, per-day lines, unlabelled hidden at 0, "Page n of m" | L | implementer |
| P3 | `SummaryPdfRenderer` with `PdfDocument`, wired into `SummaryExporter` and the menu | M | implementer |
| P4 | A device check: export a real week on the phone, open it in a PDF viewer, and have `ui-reviewer` check readability | S | owner + ui-reviewer |

### Acceptance criteria
- The layout tests (P2) cover each item in the P2 row on synthetic data, including a job with enough blocks to need three pages.
- Renderer (P3): a Robolectric test, if its shadow supports `PdfDocument`, checks that the file starts with `%PDF` and has as many pages as the layout. Otherwise, an instrumented test under `connectedDebugAndroidTest` checks the same.
- The PDF totals match the Summary screen for the same range on the phone (P4).
- Commands: as in § 4, plus `gradlew.bat connectedDebugAndroidTest` if P3 needs the device.

### Risks and open questions
- **Robolectric and `PdfDocument`:** unverified. The fallback is an instrumented test; the layout tests carry most of the coverage either way.
- **Zone:** both exports use the Summary's zone (the device zone), not each block's capture zone, so they match the screen. A block captured in Perth exports in Sydney time.
- **`duration_min` truncates to whole minutes.** If Walkflow wants exact time, add a `duration_s` column at the end, which keeps the header stable for anything reading by name.
