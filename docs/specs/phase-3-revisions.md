# Phase 3 — owner revisions after the drawer taste check (2026-10-22)

These amend `docs/specs/phase-3.md` and the approved plan. Where they conflict, this file wins. Branch: `feat/phase-3-timeline-editing`, now rebased onto `fix/dogfood-1`.

## R1 — Shell and timeline header row (implementer, S)
- **Remove the Timeline nav badge.** Remove `TarryBottomBar`'s badge param and the badge count flow in `ShellStatusViewModel`. Keep the undo snackbar feed. The needs-review count stays in the summary card. Update the tests.
- **One row below the week strip** holds two controls, side by side:
  - **Needs review** is a toggle (icon + label) replacing the "Needs review only" chip. Use a `TarryChip`/`SegmentedControl`-style toggle with `Role.Switch` semantics, stating "on"/"off". It keeps the 40 % dimming behaviour.
  - **New visit** is a button with a "+" icon and the label "New visit". It replaces the "+" `IconCircleButton` in `DayHeader` (remove that one). It opens `EditTarget.NewVisit` on the first free slot, as before.
  - Both are ≥ 48 dp. Labels never truncate at font scale 2; wrap the row, don't shrink.
- **Tracking button, Off state:** more horizontal padding (use the tokens, e.g. `space.l` → `space.xl`). The other three states are unchanged.

## R2 — Drag to resize visits on the canvas (implementer + test-writer, M)
**Gestures on visit blocks:**
- **Single tap** selects the visit. It shows top and bottom drag handles: a visible pill, with a ≥ 48 dp touch target straddling each edge.
- **Double tap** opens the drawer.
- **Long press** stays reserved for multi-select (T4).
- Tapping elsewhere, scrolling the canvas or changing the day deselects.
- A single tap is only confirmed after the double-tap timeout. That's acceptable.

**Trips and gaps** open on a single tap, as today.

**Dragging a handle:**
- It moves that edge: the top edge sets the start, the bottom edge sets the end.
- It snaps to 5 min. A floating label shows the new time, and there's a light haptic tick per step (none with reduced motion or haptics off).
- The visit can't go below `minStayDurationMs` (5 min).

**Dragging over a neighbour:**
- The previous or next visit is **trimmed** to make room, never merged and never deleted. The neighbour is clamped so it keeps at least 5 min.
- Trips in between are derived, so segmentation recomputes them.
- Frozen neighbours are trimmed too. That's an explicit user edit, so it freezes as usual.

**On release:**
- One new `TimelineEditor.resize(visitId, newStart, newEnd)` op, in one transaction: trim this visit, trim any overlapped neighbours, freeze everything it touched, unfreeze overlapped frozen trips (T1 rule), rewind the checkpoint, and return one `Snapshot`.
- The caller offers an undo ("Visit times changed · Undo") and calls `enqueueNow()`.

**Accessibility:** the drawer's Time group keeps every non-drag alternative (WCAG 2.5.7). In TalkBack, activating a visit opens the drawer; the handles aren't separate focus targets.

**Tests:**
- Editor: resize with no neighbour, into the previous neighbour, into the next neighbour, the 5 min clamp, frozen neighbour, an undo round-trip, and no overlap ever.
- Compose: tap selects and shows the handles; double tap opens the drawer; trip and gap single tap opens; drag (via `performTouchInput`) updates the label and commits once.

## R3 — Drawer layout (implementer, S–M)
- **Section order:** Job, Tags, Notes, Toggles, Time.
- **Tags:** "New tag" becomes an inset subtle surface, the same treatment as the notes field, with a centred "+" icon and "New tag", always visible.
- **Toggles:** one `SettingsGroup` under the section header "Toggles", holding Billable (with its inherited caption) and Set as default for this place.
- **Time:** unchanged rows — Trim start, Trim end, Split at…, Merge with previous/next, Change place, Delete, and Reset to detected (when allowed).
- **Name this place:** a pencil `IconCircleButton` beside the place name in the header opens a name dialog (`Place.name`, no schema change). Its content description is "Rename place". An empty name clears it and falls back to the address label.

## R4 — Sessions screen (implementer, S)
- Each session is a row with a leading icon (state: complete, killed, open).
- Its exports, "Export GPX" and "Export CSV", are a stacked vertical `SettingsGroup` of icon rows under the session, not inline buttons.
- Follow the Settings screen's look: grouped surfaces with dividers.

## Unchanged
The rest of phase 3 continues after these revisions: T5 (manual visit), then T4 (multi-select).
