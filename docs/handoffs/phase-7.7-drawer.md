# Handoff: phase 7.7 — Drawer (the 7.5 area 2 picks)

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-08 · **Branch:** `feat/phase-7.7-ui-finish`. Do this after the Timeline items in `phase-7.7-timeline-finish.md`. The same rules apply (one commit per item, explicit paths, a test per behaviour change, no instrumented tests while the Pixel is attached).

## Items (owner picks, 8 Nov 2026)

### D1. Visit drawer order: Job, Place, Time, Notes (`ui/drawer/EditDrawerContent.kt`)
- **Job:** the job chips plus the "+ Add to job" chip (D3).
- **Place** (new section, string "Place"): two rows:
  - "Change place", moved out of Time. It opens the same place picker.
  - "Usual job here" (D2), with its hint below it.
  - Show the Place section only when the visit has a place and the place isn't ignored, the same condition the default-job row uses today. If "Change place" is valid without a place (check `TimeSection`'s current condition), it still shows, alone.
- **Time:** trim start/end, split, merge previous/next. Same rows as today, minus Change place.
- **Notes:** last.

The trip drawer is unchanged except for D3 (Job, Notes, Add a stop). Job drawers are unchanged.

### D2. Default-job wording (`res/values/strings.xml`)
- `edit_place_default_job_row` → "Usual job here" (no placeholder; update the call site).
- `place_defaults_hint` → "New visits here are suggested for this job."
- Check every other use of these two strings (Places / place detail screens) still reads correctly. If the place detail screen needs the place name, give it its own string rather than reusing this one.

### D3. "+ Add to job" chip (`EditDrawerContent.kt`)
- The full-width "Add to job" row becomes the last chip in the job chips `FlowRow`: a leading "+" icon, the label "Add to job", the unselected chip style (1 dp `line` border, decision 6A), and a 48 dp touch target.
- When the segment has no jobs, the chip shows alone, and the "Not in a job yet." line (`edit_segment_no_jobs`) is removed. Delete the string if it's unused.
- Applies to visits and trips.

### Not changing
- Notes keep saving silently (owner: keep silent).

## Tests
- A Robolectric Compose test for section order in the visit drawer (Job, Place, Time, Notes headings, in traversal order), with Change place under Place.
- The add-to-job chip opens the job picker, and appears alone with no jobs.
- The string wording asserted wherever existing tests reference the old text (update them).
