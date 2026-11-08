# Phase 8 — start here (state after the 7.5/7.7 UI pass)

**From:** Claude (planner, 7.5/7.7 session) → **To:** a fresh phase 8 session · **Date:** 2026-11-08.

**Read in this order:**
1. this file;
2. `docs/handoffs/phase-8-launch.md` (scope, ADRs, definition of done; its "Owner decisions, 2026-11-08" section comes with PR #31);
3. `docs/design/ui-rules.md` and `docs/design/tokens.md`;
4. `docs/brand/brand-and-copy.md`.

## Where things stand
- **main** has phases 1–7, the 7.5 UI pass part 1 (PR #29) and timeline zoom (PR #30).
- **PR #31 (`chore/relicence-fsl`):** the repo is FSL-1.1-ALv2 (`LICENSE.md`, ADR 0005, licensor Kori Jacobsen), plus the owner's 8 Nov phase 8 decisions in `phase-8-launch.md`. Docs only. Merge before or alongside phase 8.
- **PR for `feat/phase-7.7-ui-finish`:** the end of 7.5. See "What 7.7 shipped" below. Merge it before branching `feat/phase-8-launch` from main.
- **Colours:** the theme stays Apricot (owner, 8 Nov). A palette exploration exists (artifact `https://claude.ai/artifact/PTgiSoLU9srcYVj47RHFP8`), with Slate & Ice shortlisted. It is parked, not planned.

## Owner decisions already made (don't re-ask)
- Play organisation account and D-U-N-S are verified.
- **Licence:** FSL-1.1-ALv2. Google Play is the only binary channel (no GitHub Releases APKs).
- **Launch:** worldwide, English-first, made in Australia. The app is English-only at launch.
- **Refunds:** Play's standard policy.
- **Trial:** a 7-day local trial clock, then a one-time lifetime purchase. The trial-reset weakness is accepted. No subscription for the core.

**Still open:**
- the price;
- what the trial end locks (the recommendation is tracking only, never data access);
- the seller name, support email and seller country on Play;
- the closed-test exemption, to verify in Play Console.

## What 7.7 shipped (owner picks, 8 Nov)
- **Timeline:**
  - the review chip in the summary card is the Needs review filter, and the review row is Timeline/Map plus Add job;
  - block text fills its height, and time ranges break only after the dash;
  - short-visit handles sit outside the card;
  - hour rules are in `outline`;
  - from 1.5× font, labels move above their lines and draw under the blocks;
  - trips have a dotted connector;
  - off stretches are hatched "Not tracking", and the canvas starts at the first Start;
  - the zoom pill is `inset`;
  - the week strip is one scrolling row, with two-letter days at large font;
  - Add job gets its own line at large font;
  - nav labels are no longer clipped.
- **Drawer:**
  - visit sections are ordered Job, Place, Time, Notes;
  - "Usual job here" replaces the default-job wording;
  - "+ Add to job" is a chip;
  - notes still save silently.
- **Map:**
  - TextureView maps, so they don't linger under the next screen;
  - the pin picker never saves (20, 0);
  - a theme switch keeps the camera;
  - the mini-map credit reaches 4.5:1;
  - coincident markers share one "6, 7" marker.
- **Fixes:**
  - manual-visit save has an in-flight guard, and its error clears;
  - **an edited time stays on the segment's own local date** (behaviour change: a visit can no longer be extended past midnight by editing);
  - Summary range chips scroll into view, and the billed value has a "Billed" caption at 1×;
  - Place detail counts stay whole, and its hint only shows with a default job set;
  - the Permissions amber note only shows when the permission is missing;
  - range and Sessions formats are compact.
- **Icons:** map, work, location and delete are official Material Symbols.
- **Docs:** `ui-rules.md` is new, and `tokens.md` is updated.

## Carry-overs (not done; pick up in phase 8 where they touch a screen)
- **Dark mode was not checked on the device** after 7.7. The owner's app theme is Light. Ask the owner before switching it, then do a dark pass of every screen (`ui-reviewer`).
- **Large font:** the timeline at 2× hasn't been scrolled on the device past the first screen. Check the stacked labels under blocks and the hatch.
- **Nine classic Material Icons drawables** are still to swap (`ui-rules.md` §6).
- **Nits from the final review** (owner didn't pick them for 7.7):
  - Jobs and Places rows are tappable but have no chevron;
  - an empty day doesn't say why (e.g. "Not tracking: outside your schedule", rule 6);
  - the Sessions screen shows dev-flavoured text (STILL, "ignored as stale", "Start test window"), so hide or humanise it in release;
  - the Schedule header abbreviations are inconsistent at 2×;
  - the Permissions status icons don't scale at 2×;
  - short timeline blocks are small tap targets;
  - the zoom pill sits over the job column while scrolling;
  - two jobs can share a colour (assignment).
- `firstFreeSlot` (`ManualVisitSlots.kt`) is unused except by its own tests.
- **Phase 8a's "Data" items overlap Settings:** retention that actually prunes, delete all, and backup links. Settings → Data rows exist today as display-only.

## Working rules learned the hard way
- **The phone holds the owner's real data.** Back up to `tmp/` before every install (`adb exec-out run-as me.tarry.app cat databases/tarry.db{,-wal,-shm}` plus `files/datastore/*.preferences_pb`).
- **Never swipe on the timeline canvas from adb after tapping a block.** It drags the resize handle and edits times. If it happens, tap the snackbar's Undo at once.
- **Never run `connectedDebugAndroidTest` with the Pixel attached.** Use the emulator (`Medium_Phone_API_35`).
- **Editing tools in this environment turn `\uXXXX` escapes into raw characters.** For invisible characters, use `Char(0x00A0)` and similar.
- Stage explicit paths only (never `.idea`, never `commit -a`). Never commit to main.
