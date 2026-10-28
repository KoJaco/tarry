# Phase 6b — Jobs on the timeline (job blocks), tags removed

## Context
Today a Job is only a name and a colour, attached one visit at a time (double tap → drawer → job picker). Putting a run of trips and places on one job is tedious. Trips have no automatic job at all: the `TravelAttribution` setting exists, but nothing reads it. Tags overlap with notes. The owner wants a job to be something you place on the timeline yourself (start → end), shown in a second column beside visits and trips, and tags removed so the model stays simple. This lands before phase 7 (Summary/Export), which then sums job blocks.

Order: device pass, then merge PR #23 (phase 6). Then branch `feat/phase-6b-job-blocks` from `main` in a new worktree.

## Owner decisions (2026-10-28)
- **A job block's billed time is its range** (a timesheet entry). Visits and trips inside it are the evidence.
- **Place default job → a suggested block.** A detected visit at a place with a default job shows a faint suggestion in the job column; one tap confirms it. Suggestions are computed when the day is read, never stored, and never billed.
- **Overlaps are allowed.** For example, one trip can count toward two jobs. Overlapping blocks sit side by side in lanes, and each block bills its full range.
- **Tags are removed.** The migration first appends each visit's or trip's tags to its notes ("Tags: Materials, Callout"), then drops the tag tables.
- **Agreed design:** notes stay on visits and trips, and job blocks get their own notes. Billable moves to the job block, with its default taken from the Job. "Needs review" = a non-ignored visit covered by no job block. "New visit" is replaced by "Add job".

## Data (Room v1 → v2, first schema change; `migration-guard`)
- **New `job_block` table:** `id` UUID PK, `jobId` (FK Job), `start`, `end`, `zoneId`, `billable`, `notes`, `updatedAt`, `deleted`. Indexes on `start` and `jobId`. Follows the AGENTS.md rule 3 shape.
- **`Job` gains `defaultBillable: Boolean`.** Default true; ask during implementation if unsure.
- **Migration 1→2:**
  1. Append tags to `freeNotes` for each visit and trip (`VisitTag`/`TripTag` → `Group:Tag` names).
  2. Each visit with its own `jobId` becomes a block covering the visit. Billable = `visit.billable ?: place.defaultBillable ?: false`.
  3. Each trip with `attributedJobId` becomes a block covering the trip. Billable = `trip.billable ?: false`.
  4. Drop `tag`, `tag_group`, `visit_tag`, `trip_tag` and `place_default_tag`.
  5. Drop `Visit.jobId`, `Visit.billable`, `Trip.attributedJobId`, `Trip.billable` and `Place.defaultBillable` with a table rebuild. minSdk 29 has no `DROP COLUMN`.
- **Kept:** `Place.defaultJobId`, which drives suggestions.
- **Tests:** a `MigrationTestHelper` test seeds v1 rows (tags, a visit job, a trip job, a place default) and asserts the v2 notes, blocks and dropped tables. Commit the `2.json` schema.
- **`JobBlockRepository`** in `core-data`: `observeOverlapping(from, to)` (same pattern as `TrackingSessionDao.observeOverlapping`), `save`, `delete` (soft), `byId`.
- **Remove the `TravelAttribution` setting** and its setter, since job blocks replace it. `minBillableMinutes` stays for phase 7.

## Pure logic (JVM tests)
`app/.../ui/timeline/JobBlocks.kt`:
- `jobLanes(blocks)`: interval lane assignment, for side-by-side overlaps.
- `coverage(visits, trips, blocks)`: which segments each block covers, plus `needsReview`. A visit covered by no block counts as needing review even when it has a suggestion.
- `suggestions(visits, places, blocks)`: a detected visit at a place with `defaultJobId` and no covering block of that job gives a suggestion with the visit's range.
- `assignRange(selection)`: from the earliest start to the latest end of the selected visits and trips.
- `addJobToSegment(segment, job, blocks)`: extend a block of the same job that overlaps or touches the segment (within `JobBlockConfig.joinWindowMs`); otherwise create a block equal to the segment.

## Timeline canvas (`DayCanvas.kt`, `DayLayout.kt`)
- **Columns:** time column | visits and trips (~60%) | job column (~40%). `layoutDay` keeps the vertical maths and gains `PlacedJob(lane, laneCount)`.
- **Job block:** job colour, name, duration, a billable mark. It sits behind the visit column, and the tracking markers stay behind everything.
- **Suggestion:** dashed outline plus "Suggested: <job>". Tap → confirm. Long press → dismiss for this visit, kept in memory for the session only (decide in review).
- **Interactions:**
  - Tap a job block → job drawer.
  - Long-press then drag in empty job column → draw a range, snapped to 15 min, which opens New job prefilled. Reuses the `PaintStroke`-style gesture.
  - Visits keep today's gestures (tap selects, double tap opens the drawer, long press starts multi-select).
- **Accessibility:** each block is focusable ("Job Smith reno, 9:00 to 15:00, billable"), and the suggestion's action is labelled "Confirm suggested job".
- **Hidden:** the `ReviewRow` "New visit" button becomes **"Add job"**, prefilled with the first free hour or the current hour. The add-visit form code stays, unreached from the review row. The gap sheet's "Add visit" and the trip's "Add a stop" stay, since they fill holes; the owner can hide them too.

## Drawer (`ui/drawer/`)
- **New `EditKind.JOB` / `NEW_JOB`:**
  - job picker with inline create (reuses the current picker and `createJob`);
  - start and end (the `NewVisitForm` time pickers);
  - billable toggle, defaulting to `Job.defaultBillable`;
  - notes;
  - "Covers 3 visits · 2 trips" (read-only);
  - Delete with undo (reuses `UndoEvent`).
- **Visit drawer:**
  - The Job section lists the covering blocks as chips; tapping one opens that job drawer.
  - "Add to job" → picker → `addJobToSegment`.
  - "Set as place default job" stays.
  - Removed: tags, billable toggle, inherited-job text.
- **Trip drawer:** the attribution picker becomes the same Job section. Tags and billable are removed. Notes and "Add a stop" stay.
- **Multi-select bar:**
  - "Set job" becomes **"Assign job"**: one block from `assignRange`.
  - "Add tags" is removed, and so is any bulk billable action.
- **Jobs screen:** a per-job "Billable by default" toggle.

## Tags removal (everywhere)
Delete the Tags screen and route, the Settings row, `TagsSection`, `TagMultiSelectSheet`, `DefaultTagsSheet`, bulk add-tags, `TagRepository`/`TagDao`, the tag paths in `CatalogEditor`, `TimelineEditor.SetTags/AddTags`, the place-default-tag paths, and the segmentation copy of default tags, job and billable (`SegmentationRunner.kt:201-210`). Detected visits no longer carry job or billable, so re-segmentation can't lose labels. Delete the tests for removed code.

## Docs
- `docs/specs/phase-6b.md`: a copy of this plan.
- `docs/specs/build-plan.md`: a status line and a spec delta: job blocks replace per-segment job, billable, attribution and tags.
- `docs/specs/tarry-mvp.md`: update the deltas note for § Travel attribution, § Calculation rules and the CSV `tags` column (removed; the CSV gains `job_block_id`).
- `AGENTS.md` rule 3: swap Tag and TagGroup for JobBlock.

## Tasks
| # | Task | Agent |
|---|---|---|
| T0 | After #23 merges: worktree and branch, spec copy, build-plan line | main |
| T1 | Schema v2, migration, `JobBlockRepository`, migration test | migration-guard → test-writer |
| T2 | Remove tags, per-segment job, billable and attribution from editor, segmentation, settings and UI; fix the tests | implementer → test-writer |
| T3 | `JobBlocks.kt` pure logic with JVM tests | implementer → test-writer |
| T4 | Two-column canvas, job lanes, suggestions, drag-to-create; Compose tests | implementer → test-writer → ui-reviewer |
| T5 | Job drawer, visit and trip drawer changes, Add job button, Assign job, Jobs-screen default billable; VM and Compose tests | implementer → test-writer → ui-reviewer |
| T6 | Review, then device pass (back up the DB to `tmp/` first, since the migration runs on real data), then PR | reviewer, deployer |

## Verification
- `gradlew.bat assembleDebug testDebugUnitTest lintDebug` passes, and CI is green.
- The migration test passes from v1 with seeded tags, jobs and billable.
- **On the Pixel:**
  - Pull the DB to `tmp/pre-phase-6b/` before install.
  - After install, check that the dogfood visits that had jobs now show job blocks, and that tags appear in notes.
  - Add a job 9:00–15:00 across several visits and trips; the visits lose the review dot.
  - Draw a job by drag.
  - Make two jobs overlap; they show in two lanes.
  - Confirm a suggestion at a place with a default job.
  - Visit drawer → Add to job extends the neighbouring block.
  - Multi-select → Assign job.
  - Delete a job, then undo.
  - TalkBack on job blocks; font scale 2.

## Follow-ups (listed, not fixed)
The 24 Oct no-Gap drive bug (`debug-triage`, `fix/` branch); the `DayCanvas.kt:553` always-true warning; the phase 5 nits in the handoff.
