# Handoff: phase-4a-selection-catalog

**From:** Claude (planner) → **To:** implementer (Claude subagents, or Cursor) · **Date:** 2026-10-22 · **Branch:** `feat/phase-4a-selection-catalog`

## Goal
Phase 4a, the first of two PRs. Finish phase 3's multi-select and give jobs and tags proper management screens: rename, recolour, archive, delete when unused, and reorder tags and groups. Also fix the dropped segmentation re-run. Places (radius, merge, delete, prune) are phase 4b, in a separate PR after the owner dogfoods 4a. The full spec, decisions and design notes are in `docs/specs/phase-4.md`.

## Constraints
- The AGENTS.md hard rules apply.
- **No schema change.** The DB stays at v1 and `core-data/schemas/.../1.json` must not change.
- No new dependencies. The reorder is hand-rolled.
- Every editor op runs in one `db.withTransaction`, re-reads its rows by id, and returns an `EditResult` (never throws to the UI).
- Archived jobs and tags stay on past visits (the timeline and drawer show them), but pickers hide them unless they're the current value or already applied.
- Archiving a job or tag clears it from place defaults, and undo restores those defaults.
- Accessibility (WCAG 2.2 AA):
  - Targets are ≥ 48 dp.
  - Every control has a label.
  - Nothing truncates at font scale 2.
  - Reorder has TalkBack "Move up" / "Move down" custom actions as the non-drag alternative (WCAG 2.5.7).
- **Out of scope:**
  - place radius, merge, delete and prune, and the Places screen (4b; the Settings "Places" row can wait until 4b);
  - the map;
  - summary maths.

## Files to touch
- `app/.../segmentation/SegmentationScheduler.kt`: `enqueueNow` changes from `ExistingWorkPolicy.KEEP` to `APPEND_OR_REPLACE`.
- `app/.../ui/timeline/DayCanvas.kt`, `TimelineContent.kt` and `TimelineViewModel.kt`, plus a new `SelectionBar.kt`: selection mode.
- `app/.../ui/TarryApp.kt`: the selection bar replaces the bottom bar while selecting. Also the new `JobsRoute` and `TagsRoute` entries.
- `app/.../ui/navigation/Routes.kt`: `JobsRoute`, `TagsRoute`.
- `app/.../ui/settings/SettingsScreen.kt`: a new "Labels" group with Jobs and Tags rows.
- New `core-data/.../repository/CatalogEditor.kt`.
- `core-data/.../db/dao/CatalogDaos.kt`: case-insensitive ordering, usage counts, places by default job, default-tag links by tag.
- `core-data/.../repository/TimelineEditor.kt`: only the `Snapshot` / `restore` extension (`jobs`, `tags`, `placeDefaultTags`; restore replaces default tags only for the places listed).
- `app/.../ui/drawer/EditDrawerViewModel.kt`: the catalog filters archived jobs and tags. Also a tag multi-select sheet in `DrawerDialogs.kt` for the bulk "Add tags".
- New `app/.../ui/catalog/JobsScreen.kt`, `JobsViewModel.kt`, `TagsScreen.kt` and `TagsViewModel.kt`, plus `app/.../ui/components/ReorderableList.kt`.
- **Do not touch:**
  - the entities;
  - `Migrations.kt`;
  - the schema JSON;
  - the `:segmentation` logic;
  - `SEGMENTATION_VERSION`.

## Steps
1. **T1, the scheduler policy.** Add a test showing that a second enqueue while a run is in progress gets chained, not dropped. Cancel WorkManager work in `@After`, or it leaks into later Compose tests.
2. **T2, multi-select.**
   - **Gestures:** long press on a visit block enters selection mode (`onLongClickLabel` "Select for bulk edit"). While selecting, a tap toggles a block, and blocks show a check. Back, Cancel or changing the day exits selection mode.
   - **Interaction with phase 3 R2:** the single-tap select and drag handles are suppressed during selection.
   - **`SelectionBar` actions:** Set job, Add tags, Billable, Not billable, Ignore place, Cancel. Each one is a single `editor.bulk(...)` with one undo event, then the bar exits.
   - **Layout:** the bar wraps at font scale 2.
3. **T3, `CatalogEditor` with tests** (`CatalogEditorTest`).
   - **Jobs:**
     - `renameJob` (a blank name gives `BlankName`) and `setJobColour` (a `JobPalette` key);
     - `archiveJob` and `unarchiveJob`; archive clears `Place.defaultJobId` and returns a Snapshot for undo;
     - `deleteJob`, a soft delete; if any live visit `jobId`, trip `attributedJobId` or place default uses the job, it returns `InUse(count)`.
   - **Tags:**
     - `renameTag`;
     - `archiveTag` and `unarchiveTag`; archive removes the tag's `place_default_tag` rows, and the Snapshot keeps them;
     - `deleteTag`, only when it's unused.
   - **Tag groups:**
     - `createTagGroup`, `renameTagGroup` and `setTagGroupColour` (a `JobPalette` key, or 0 for none);
     - `archiveAllTags(groupId)`;
     - `deleteTagGroup`, only when the group has no non-deleted tags, otherwise `InUse`.
   - **Reorder:** `reorderTags(groupId, orderedIds)` and `reorderTagGroups(orderedIds)`. If the ids don't match the live set, return `NotFound`. Otherwise write `sortOrder = index` for the rows that changed. There's no undo.
   - **Job order:** jobs sort with `COLLATE NOCASE`.
   - **`EditResult.InUse`:** add it.
4. **T4, picker filtering.** The drawer catalog and the bulk sheets hide archived jobs and tags unless they're current or applied. Add a test in `EditDrawerViewModelTest`.
5. **T5, the Jobs screen.**
   - **Entry point:** a Settings "Labels" group with Jobs and Tags rows.
   - **List:** `JobSwatch` and name, then an "Archived" section.
   - **Row actions sheet:**
     - Rename, using `NameDialog`;
     - Colour, a grid of `JobPalette` swatches with spoken slot names;
     - Archive or Unarchive, with a confirm that shows how many places' defaults get cleared;
     - Delete, disabled with a reason when the job is in use.
   - **New job:** reuses `TimelineEditor.createJob`.
   - **Undo:** goes through `UndoEvents`.
6. **T6, `ReorderableList` and the Tags screen.**
   - **Reorder behaviour:** long press on the drag handle starts a drag. Rows swap at the midpoint of the neighbouring row, and there's one commit on drop. TalkBack gets "Move up" and "Move down" custom actions. Respect reduced motion. Tags stay within their group.
   - **Tags screen:** group sections, each with a header (name, colour, a menu with Rename / Colour / Archive all / Delete) and the group's tag rows (handle, chip, menu with Rename / Archive / Delete). Archived tags show dimmed, with Unarchive. There are New group and New tag actions.
   - **Tests:** `ReorderStateTest` (the pure function), plus Compose tests for the custom actions and for a `performTouchInput` drag, each committing once.
7. **T6r, review.**
   - deployer installs on the Pixel;
   - ui-reviewer checks light and dark, font scale 2 and TalkBack over the selection bar and the reorder;
   - reviewer checks the branch against `docs/specs/phase-4.md` and AGENTS.md;
   - open the PR.

## Definition of done
- [ ] Behaviour matches `docs/specs/phase-4.md` 4a decisions.
- [ ] Tests: `SegmentationSchedulerTest`, `CatalogEditorTest`, `TimelineEditorTest` (bulk AddTags/SetBillable/IgnorePlace undo round-trip), `EditDrawerViewModelTest`, `JobsViewModelTest`, Compose tests for selection, Jobs and Tags screens, `ReorderStateTest`; `SettingsUiTest` updated.
- [ ] `gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` green locally and in CI; `1.json` unchanged.
- [ ] ui-reviewer pass on device (light/dark, font scale 2, TalkBack); screenshots only in `tmp/`.

## Verification commands
```
gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug
git diff --exit-code main -- core-data/schemas
gradlew.bat installDebug
```

## Known risks / questions
- The long press on visit blocks has to coexist with phase 3 R2's tap-to-select, double-tap and drag-handle gestures in a single `detectTapGestures`. Check that long press doesn't start a resize.
- Hand-rolled drag in a `LazyColumn`: auto-scroll near the edges is nice to have, not required.
- **Dogfood feedback** from 2026-10-23 may change 4a's priorities. Check with the owner before starting T5 and T6.
