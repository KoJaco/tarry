# Phase 3 — Timeline editing (drawer, annotations, manual edits)

Branch: `feat/phase-3-timeline-editing`, from `main`. Size: L. Parent: `docs/specs/build-plan.md` (3). Product source: `docs/specs/tarry-mvp.md` § Frozen records, § Timeline, § Edit drawer. Design source: `docs/design/tokens.md` and the 2c components. Every screen builds only from those.

## Goal
Turn the read-only day canvas into the place where a day is reviewed and labelled for invoicing. The user taps a visit or trip and labels it with a job, tags, billable and notes. They can fix the times: trim, split, merge, reassign the place, delete, or add a manual visit. Every edit freezes the record so segmentation never overwrites it, and destructive edits can be undone. "Needs review" becomes something you can work through: a filter, a nav badge and bulk actions.

## Non-goals
- Jobs, tags and places management screens (CRUD, reorder, archive, radius slider, merge places): phase 4. Phase 3 only creates jobs and tags inline from the drawer.
- Map, mini maps and map-pin manual visits: phase 5. The drawer header has no map in phase 3.
- Week and month ranges, the Summary view and export: phases 6–7.
- Travel-attribution maths. Phase 3 stores the per-trip override; phase 7 uses it.

## Decisions (planner, 2026-10-21; confirm the ⚑ ones with the owner before T1)
- **The day canvas stays the timeline.** The MVP spec's "List view" was superseded in 2c by the calendar canvas, and phase 3 adds editing to the canvas. There is no separate list view.
- **Job, not free-text label.** The schema stores `Visit.jobId` → `Job` (name and palette colour). The drawer's job field is a picker over jobs with "New job…", which creates a `Job` with the next unused `JobPalette` slot. ⚑ Confirm that inline job creation in phase 3 is wanted (otherwise the picker only lists jobs, and jobs come in phase 4).
- **Resolved annotation:** visit value if set, else the place default (`Place.defaultJobId`, `defaultBillable`, `place_default_tag`). The drawer shows inherited values as such ("From place: …") and setting a value freezes the visit. The "Set as default for this place" checkbox writes the place default.
- **Freeze rules** (MVP § Frozen records):
  - any annotation or manual edit sets `frozen = true` and bumps `updatedAt`;
  - delete is a soft delete (`deleted = true`) plus an `ExcludedRange` over its span, so it is not re-detected;
  - "Reset to detected" (confirm dialog) clears the annotation, sets `frozen = false`, removes the visit's exclusions and enqueues segmentation for that day.
- **Segmentation after edits:** enqueue the existing `SegmentationScheduler.enqueueNow()` after each structural edit (trim, split, merge, delete, manual visit, reset). 2b already treats frozen ranges and exclusions as fixed, so check that with a golden-case test.
- **Undo:** a snackbar ("Visit deleted · Undo") for destructive edits (delete, split, merge, trim, reassign, reset), held for 8 s. Undo restores the exact previous rows (a snapshot of the affected visits, trips, tags and exclusions), not an inverse operation. Annotation edits save immediately and need no undo.
- **Trips** use the same drawer: tags, billable, notes, and an attribution override (job picker, or "Unattributed"). There are no time edits on trips in phase 3, because trips are derived between visits.
- **Gaps** open a small read-only sheet (cause, times, what it means). No edits.
- **"Needs review"** = a non-deleted visit, not on an ignored place, with no resolved job. Inner gaps (2c) don't count.
- ⚑ **Manual visit entry point:** a "+" `IconCircleButton` in the day header opens the drawer in create mode: place search over known places, plus "Here now" (last ping location). Placing a visit by long-pressing an empty canvas slot is a nice-to-have, not required.
- **Schema:** no change is expected (v1 already has Job, Tag, TagGroup, VisitTag, TripTag, ExcludedRange, the place defaults, frozen and origin). If one turns out to be needed, it goes through `migration-guard` (rule 4) and gets flagged in the PR.

## Tasks (in order)

### T1 — Edit use cases (implementer + test-writer, M)
- `core-data`: a `TimelineEditor` (or `VisitEditor` + `TripEditor`), with each op in one Room transaction:
  - `setJob`, `setBillable`, `setNotes`, `setTags` (visit and trip);
  - `setPlaceDefault(job | billable | tags)`;
  - `trim(visitId, newStart, newEnd)`, clamped to its neighbours, never overlapping another visit;
  - `split(visitId, at)`, giving two frozen visits with `origin = SPLIT` (annotations copied, tags copied);
  - `merge(visitId, withPrevious|withNext)`: one frozen visit, `origin = MERGED`, union of tags, the earlier visit's job unless it is null;
  - `reassignPlace(visitId, placeId)`;
  - `delete(visitId)`: soft delete plus an `ExcludedRange`;
  - `createManual(placeId, start, end)`: `origin = MANUAL`, frozen, no overlap;
  - `resetToDetected(visitId)`;
  - `snapshot(ids)` / `restore(snapshot)` for undo.
- Validation errors are a sealed result (overlap, empty range, out of day), never exceptions to the UI.
- Tests: in-memory Room for every op (freeze flag, `updatedAt`, tags, exclusions, no overlap, undo round-trip), plus a segmentation golden case showing that a frozen or trimmed visit and a deleted range survive a re-run untouched.

### T2 — Drawer (implementer, L)
- `ui/drawer/`: `EditDrawer` (M3 `ModalBottomSheet`, `container` radius, half → full), stateless content plus `EditDrawerViewModel` (`SavedStateHandle` id and type).
- Header: the place name (`titleLarge`), time range and duration (tabular), and a needs-review chip when unlabelled.
- Sections built from 2c components:
  - **Job:** a picker sheet with `SettingsGroup`-style rows and job colour swatches, plus "New job…";
  - **Tags:** grouped `TarryChip`s, multi-select, with inline "New tag";
  - **Billable:** `SettingsSwitchRow` showing the inherited state;
  - **Notes:** an inset multi-line field;
  - **Time:** rows for Trim start, Trim end, Split at…, Merge with previous/next, Change place, Delete (`DestructiveButton`), and Reset to detected (frozen visits only).
- Time pickers: the M3 `TimePicker` dialog in the user's 12/24 h setting.
- Accessibility: every control is labelled, tag chips are toggleable, and the sheet title is a heading.

### T3 — Canvas integration (implementer, M)
- Tapping a visit or trip block opens the drawer, and tapping a gap block opens the gap sheet. Blocks get `Role.Button` and an `onClick` label ("Edit visit").
- Undo snackbar host on the timeline.
- Frozen or annotated blocks render their job colours immediately: they already read job fields, so check this live.
- A "Needs review only" `TarryChip` above the canvas dims everything else to 40 % (it does not hide, so the scale stays readable).
- The Timeline nav item gets a badge with today's needs-review count (`accentSoft` / `onAccentSoft` pill, with a content description such as "Timeline, 3 visits need review").

### T4 — Multi-select and bulk actions (implementer, M)
- Long-press a visit block to enter selection mode. Blocks show a check and taps toggle selection, and a bottom action bar replaces the nav: Set job, Add tags, Billable on/off, Ignore place, Cancel.
- Each bulk action goes through the T1 ops in one transaction, with one undo.

### T5 — Manual visit (implementer, S)
- The "+" in the day header opens the drawer in create mode: pick a place (search over known places by name/address; "Here now" uses the last ping location) plus start and end. It is validated by T1 (no overlap).

### T6 — Review (ui-reviewer + reviewer, S)
- On the Pixel: label a real day end to end, in light and dark at font scale 1.0 and 2.0, with a TalkBack pass over the drawer and the bulk bar. Screenshots go only in `tmp/`.
- The reviewer checks the whole branch against this spec and AGENTS.md.

## Acceptance criteria
- `./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` passes locally and in CI.
- Every edit freezes its record. A segmentation re-run never changes a frozen visit or re-detects a deleted range (golden test).
- Undo restores the exact previous state for every destructive edit (tests).
- No overlap is possible through trim, split, merge or a manual visit (tests).
- Room schema unchanged, or a version bump with a migration and a `MigrationTestHelper` test.
- On the Pixel: a full day can be labelled in under a minute per visit, and needs-review reaches 0 with the badge clearing.

## Risks and open questions
- **Editing a visit that segmentation is about to rewrite:** the worker must skip frozen rows atomically. Check that the 2b `SegmentationRunner` reads `frozen` inside its transaction.
- **Trim/merge across inner gaps:** a merged visit can contain health gaps, and 2c's inner-gap dots and the description already handle that.
- **Place search** has only known places until phase 5's map. That is fine for dogfooding.
- **Owner questions (⚑ above):** inline job creation, and the manual-visit entry point.
