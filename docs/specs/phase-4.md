# Phase 4 — Jobs, tags and places management (+ phase 3 T4 multi-select)

Branches: `feat/phase-4a-selection-catalog` (T1–T6r), then `feat/phase-4b-places` from main (T7–T12). Size: L. Parent: `docs/specs/build-plan.md` (4). Product source: `docs/specs/tarry-mvp.md` § Settings (Tags, Places), § Timeline (multi-select). Approved 2026-10-22.

## Context
Phase 3 (timeline editing) is merged, apart from T4, multi-select with bulk actions. The build plan's next phase is "Jobs, tags and places management" (`docs/specs/build-plan.md:43`, `tarry-mvp.md:249-250`). Today jobs and tags can only be created inline from the drawer. Nothing renames, recolours, archives, deletes or reorders them. Places can only be renamed and given defaults from the drawer. There is no radius control, merge or delete. The owner dogfoods tomorrow, so the phase ships as **two PRs**, which lets feedback land in between:
- **4a:** selection, jobs and tags.
- **4b:** places.

## Owner decisions (2026-10-22)
- **Multi-select:** phase 3 T4 is folded in.
- **Radius control:** a slider plus live counts. There's no map until phase 5.
- **Radius changes re-run unfrozen history** for the place **and its neighbours** within reach. Frozen visits never change.
- **Delete place:**
  - Soft-delete the place. Its unfrozen visits are dropped and detected again; frozen visits keep the place.
  - Auto-prune orphan places that have no name, no defaults, not ignored, the default radius and no live visits, after a 1 h grace period.
- **Reorder:** a hand-rolled drag, plus TalkBack "Move up/Move down" custom actions. No new dependency.
- **Jobs:**
  - Rename, recolour and archive.
  - Delete only when unused.
  - Sorted by name, case-insensitive.
- **Archiving a job or tag also clears it from place defaults.** Undo restores them. Past visits keep it, and it stays hidden from pickers unless it's the current value or already applied.
- **Merge places:**
  - The target's circle grows to cover both places, capped at 300 m. The confirm dialog warns when the cap applies.
  - The target's fields win, and gaps are filled from the source.
- **Tag groups:** create, rename, recolour (JobPalette slots or none), reorder and "Archive all tags". Delete only when the group has no tags left.

## Design (verified against the code)
- **No schema change:** the DB stays at v1 and `core-data/schemas/.../1.json` is unchanged.
  - A merged place becomes a deleted tombstone with `mergedIntoId` set.
  - `Snapshot` gains `jobs`, `tags` and `placeDefaultTags` (in memory only). Only the places listed get their default tags replaced on restore.
- **`core-data` gets `implementation(project(":segmentation"))`** so it can use `haversineMeters`. `:segmentation` stays pure JVM.
- **Every op is one `db.withTransaction` that re-reads rows by id.** Ops never upsert a `Place` the UI is holding, because the runner rewrites the centroid and `lastVisitAt`.
- **`rewindForPlaces(ids)`:** start from the earliest live unfrozen visit at those places. If there is none, nothing rewinds.
  - Undo uses the existing `snapshotRange(from, now)` and `restore()`. That stays exact even after a re-run.
  - The caller then calls `enqueueNow()` and offers the undo.
- **`mergePlaces(S→T)`:**
  1. Work out the new centroid as the duration-weighted mean of both places.
  2. Set the radius to `max(dist(c, c_p) + r_p)`, clamped to `[T.radius, 300]`.
  3. Fill T's empty fields from S. `ignored` stays as T's.
  4. Repoint all of S's visits to T, frozen ones included. Only `placeId` and `updatedAt` change.
  5. Mark S `deleted, mergedIntoId = T`. Repoint any places merged into S to T.
  6. `rewindForPlaces({T})`.
- **`deletePlace`:**
  1. Soft-delete the place and its unfrozen visits, with no `ExcludedRange`.
  2. Rewind.
  3. The copy explains that detected visits will come back, and points to Ignore for places that aren't work.
- **`setPlaceRadius(id, r)`:**
  - Coerce `r` to 20–300 m in steps of 5 m. Rewind P and any neighbour N where `dist ≤ max(old, new) + r_N`.
  - The sheet commits only on Save.
- **Radius preview:** visits store no coordinates, so `PlaceRadiusPreview` takes the mean of the pings in each visit's span and counts:
  - visits that stay;
  - visits that would leave;
  - visits from nearby places that would join;
  - edited visits that won't change.
- **Prune:** one `UPDATE` at the end of `SegmentationRunner.runLocked`, inside its transaction. It uses `placePruneGraceMs` (1 h) in `TrackingConfig`, and it doesn't bump `SEGMENTATION_VERSION`.
- **Bug fix:** `SegmentationScheduler.enqueueNow()` uses `ExistingWorkPolicy.KEEP` (`SegmentationScheduler.kt:36`), so a re-run asked for during a running run is dropped. This already affects phase 3 edits. Switch to `APPEND_OR_REPLACE`; the runner mutex still stops runs overlapping.
- **Deleted places on the timeline:** frozen visits at a deleted place would lose their label, because `PlaceDao.observeAll` filters out deleted places. Add a label query with no deleted filter.
- **Known consequence:** unfrozen visits detected again under another place pick up that place's defaults. The radius and merge dialogs say so.

## Tasks
| # | Task | Size | Owner |
|---|---|---|---|
| T0 | Write `docs/specs/phase-4.md` (this plan plus the decisions) and the handoff `docs/handoffs/phase-4-*.md`; update the memory | S | planner |
| **4a** branch `feat/phase-4a-selection-catalog` | | | |
| T1 | `enqueueNow` uses `APPEND_OR_REPLACE`, with a test showing a second request during a running run is chained, not dropped | S | implementer + test-writer |
| T2 | Multi-select: long press on a visit block (`DayCanvas.kt` `detectTapGestures`, `onLongClickLabel` "Select for bulk edit"); taps toggle; back, Cancel or a day change exits. New `ui/timeline/SelectionBar.kt` replaces the nav in `TarryApp.kt` with Set job, Add tags, Billable, Not billable, Ignore place and Cancel. Each action is one `editor.bulk` with one undo. Targets ≥ 48 dp and the bar wraps at font scale 2 | M | implementer → test-writer → ui-reviewer |
| T3 | New `core-data/.../repository/CatalogEditor.kt`:<br>• jobs: rename, colour, archive/unarchive (clearing place defaults), delete if unused (`EditResult.InUse(count)`);<br>• tags: rename, archive (clearing place default tags), optional delete if unused;<br>• groups: create, rename, colour, archive all, delete if empty;<br>• `reorderTags` / `reorderTagGroups`, which reject stale ids.<br>DAO additions in `CatalogDaos.kt`: NOCASE order, usage counts, places by default job. `Snapshot`/`restore` extensions in `TimelineEditor.kt` | M | implementer + test-writer |
| T4 | Pickers (drawer catalog in `EditDrawerViewModel.kt`, the bulk sheets) hide archived jobs and tags unless they're current or applied | S | implementer + test-writer |
| T5 | New Settings group "Labels" with Jobs, Tags and Places rows. `JobsRoute` with `ui/catalog/JobsScreen.kt` and `JobsViewModel.kt`:<br>• list with swatches and an archived section;<br>• actions sheet: rename, colour grid with spoken slot names, archive (with the count), delete (disabled with its reason);<br>• New job | M | implementer → ui-reviewer |
| T6 | New `ui/components/ReorderableList.kt`: long press on the handle drags, rows swap at the midpoint, one commit on drop, custom actions, reduced motion respected. `TagsRoute` with `ui/catalog/TagsScreen.kt` and `TagsViewModel.kt`: group headers, tag chips with handles and menus, archived tags dimmed with Unarchive | L | implementer + test-writer → ui-reviewer |
| T6r | Review 4a: on the Pixel, ui-reviewer checks light/dark, font scale 2 and TalkBack; reviewer checks against the spec and AGENTS.md. PR. The owner dogfoods | S | ui-reviewer, reviewer |
| **4b** branch `feat/phase-4b-places` (from main after 4a merges) | | | |
| T7 | `TimelineEditor`: `setPlaceRadius`, `mergePlaces`, `deletePlace`, `rewindForPlaces`. DAOs for live visits by place and the earliest unfrozen start. Add the `core-data → :segmentation` dependency | L | implementer + test-writer; migration-guard checks there's no schema diff |
| T8 | Prune at the end of the runner, plus `placePruneGraceMs` | M | implementer + test-writer |
| T9 | Runner integration tests:<br>• shrinking a place splits only unfrozen stays, and frozen rows stay byte-identical;<br>• after a merge, no place reappears where the source was;<br>• after a delete, unfrozen stays get a new place and frozen visits keep the deleted one;<br>• a second re-run changes nothing | M | test-writer |
| T10 | Labels for frozen visits at deleted places (`PlaceDao` label query, `TimelineViewModel.kt`, the drawer header hides Rename) | S | implementer + test-writer |
| T11 | `PlacesRoute` and `PlaceDetailRoute(id)` in `ui/places/`:<br>• **list:** sorted by last visit, "N visits · last …", an Ignored chip;<br>• **detail:** name, default job, billable (Not set / Billable / Not billable), default tags, Ignore, Merge into… (`PlacePickerSheet` without this place, confirm shows the radius and any cap warning), Delete (confirm, undo, close);<br>• **`PlaceRadiusSheet`:** a slider plus −/+ `IconCircleButton`s, live counts, Save/Cancel, a hint below 60 m;<br>• **`app/.../segmentation/PlaceRadiusPreview.kt`.**<br>The detail screen closes if its place is pruned | L | implementer + test-writer → ui-reviewer |
| T12 | Review 4b: a device pass, the reviewer, and migration-guard confirming the schema is unchanged. PR | S | ui-reviewer, reviewer, migration-guard |

Reuse what exists:
- **Drawer dialogs** (`ui/drawer/DrawerDialogs.kt`): `JobSwatch`, `JobPickerSheet`, `PickerRow`, `PlacePickerSheet`, `NameDialog` and `ConfirmDialog`.
- **Shared components:** `SettingsGroup` and `SettingsRow`, `TarryChip` and `IconCircleButton`.
- **Colours:** `JobPalette` for job and group colours.
- **Undo plumbing:** `UndoEvents` and `UndoSnackbar`.
- **Navigation:** the `SessionsRoute` sub-screen pattern in `Routes.kt` and `TarryApp.kt`.
- **Promote** `SettingsScreen`'s private `PickerSheet` to shared code only if a second caller needs it.

## Verification
Each PR has to pass these checks:
- **Build and tests:** `gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug`, locally and in CI, with `1.json` unchanged.
- **Tests that must exist:**
  - `CatalogEditorTest`: archive clears defaults and undo restores them; in-use delete is blocked; a group is deleted only when empty; stale reorders are rejected.
  - `TimelineEditorTest` (place ops): exact undo, including after a simulated re-run.
  - `SegmentationRunnerTest`: prune, and the integration cases in T9.
  - `SegmentationSchedulerTest`.
  - Compose tests for selection, the reorder custom actions and drag, radius −/+, and the delete confirm.

Device checks (deployer installs on the Pixel):
- **4a:** select 5 visits, set a job, undo it; archive a job and check it's gone from pickers but still on past visits; reorder tags by drag and with TalkBack.
- **4b:** merge two duplicate places and check that the next re-run brings no duplicate back; shrink a place and check that only unedited history re-splits; delete a junk place.
