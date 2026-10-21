# Handoff: phase-2c-design-system

**From:** Claude (planner, previous session) → **To:** a fresh Claude Code session (planner) running the implementer, test-writer, ui-reviewer and reviewer subagents · **Date:** 2026-10-21 · **Branch:** `feat/phase-2c-design-system`, created from `main` after PR #7 (docs) merges

## Start here
1. Read `AGENTS.md`, `CLAUDE.md`, then **`docs/specs/phase-2c.md`** (the tasks) and **`docs/design/tokens.md`** (every value). This handoff only adds context and gotchas.
2. `git switch main && git pull && git switch -c feat/phase-2c-design-system`.
3. Work through T1 → T7 in order, one subagent per task as the spec names it. Commit per task. Run the reviewer before opening the PR.

## Goal (one paragraph)
Replace the stock purple M3 theme with Tarry's design system and restyle every existing screen. The palette is warm paper, ink and apricot. Type is Instrument Serif over Inter. Surfaces are flat cards and pills. There is a full-width bottom bar with three equal pill slots, and a calendar-style day canvas drawn to scale. No behaviour, data or schema changes. Phases 3–8 then build only on these tokens and components.

## Where the project is (2026-10-21)
- **Merged:** phases 0, 1, 2a (capture, PR #4), the 2a dogfood fixes (PR #5) and 2b (segmentation, PR #6). PR #7 (docs: status, this spec, tokens, this handoff) is open or just merged.
- **Pending on the owner:** after 2c, a full day of dogfooding, then phase 3 (timeline cards and edit drawer).
- **Test counts on main:** app 338, core-data 62, segmentation 103. All green, no `@Ignore`.
- **Current UI files to restyle:**
  - `app/src/main/java/me/tarry/app/ui/`: `TarryApp.kt`, `shell/` (TarryBottomBar, TrackingToggle, TrackingSheet, ShellViewModel), `timeline/` (TimelineContent, TimelineScreen, TimelineViewModel), `settings/`, `permissions/`, `sessions/`, `theme/` (Color, Theme, Type: still the Android Studio template, with `dynamicColor = true`).
  - Notifications are in `tracking/TrackingNotifications.kt` and `tracking/HealthAlertNotifier.kt`.
- **Settings:** `Settings.theme` (SYSTEM/LIGHT/DARK) exists in `core-data/.../settings/` but is not wired to the theme. T2 wires it.
- **Known literal colours:** `ui/shell/TrackingToggle.kt` (amber for PAUSED). They go away in T4.

## Design decisions already made (do not re-ask)
- **Owner choices:** direction A's palette and type, direction C's calendar timeline, and a full-width bottom bar in the background colour, set apart only by a top border, with equal pill slots (Timeline | tracking toggle | Settings).
- **Fixed palette:** no dynamic colour.
- **Accent rules:** the accent (apricot `#F2B064`) is only for tracking state and "needs you" cues, never for ordinary buttons. Text on apricot is ink, never white.
- **PAUSED toggle:** `inset` fill with an apricot pause glyph, not amber, because amber and apricot are too close. It stays distinct through fill, glyph and the absence of a ring.
- **Fonts are bundled** (rule 2: no network apart from map tiles and the Geocoder). Downloading the TTFs once while developing is fine.
- **Reference mock-ups:** the private artifact "Tarry design directions" (https://claude.ai/artifact/E8AfMdmdtLaMen6F6N5SkU), rows A, B and C, each with light, dark and parts. It is reference only; `tokens.md` wins where they differ.

## Constraints and project rules that bite
- **Never commit to `main`.** One branch per phase.
- **Commit and PR footers:** commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Real location data never enters the repo:** no traces, and no screenshots of real days. `tmp/` is gitignored and holds the owner's exports and design inspiration. Commit only synthetic data.
- **Room:** no schema change in 2c. Check that `git diff main -- core-data/schemas` is empty.
- **Rules 5 and 11:** tunables (DayLayoutParams) live in one params object; WCAG 2.2 AA with ≥48 dp targets.
- **Secrets:** never read or write `.env*`, keystores or tokens.

## Environment gotchas (Windows 11, Git Bash)
- **Build commands:**
  - `./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` (about 2–4 min);
  - install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`;
  - the device is a Pixel 10 (serial `57151FDCR0075V`, Android 17, API 37).
- **No Python and no `bc`.** Use awk for quick maths. A WCAG contrast awk one-liner was used to check `tokens.md`; the T2 contrast test replaces it.
- **CRLF files:** many repo files use CRLF line endings, so multi-line `perl -0pi` edits need `\r?\n`. Prefer the Edit tool.
- **Long paths:** `git worktree remove` can fail with "Filename too long" on build output. Delete with a PowerShell script file containing `Remove-Item -LiteralPath '\\?\C:\...' -Recurse -Force`, because the Bash escaping drops a backslash.
- **Parallel subagents in one worktree:** tell each one which files are theirs and to stage by explicit path. This worked well in 2b. For truly independent streams use a worktree, and copy `local.properties` into it.
- **Merging main into a branch** can silently restore old DI bindings. One did in 2b (`NoOpSegmentationTrigger`) and broke KSP in CI. After any merge, run the full build before pushing.

## Suggested sequencing
- **Order:** T1 and T3 are quick and independent, so run them in parallel. T2 next; it unblocks everything. T4 then T5: T5 depends on T4's components, but the pure `DayLayout` math can start alongside T4. T6 after T4. T7 last.
- **Owner taste checks:** after T4, publish component previews, or a screenshot set under `tmp/`, for the owner before restyling every screen. After T5, install on the Pixel for a look at a real day.

## Definition of done
Everything in `docs/specs/phase-2c.md` § Acceptance criteria, then a PR to `main` with green CI. Afterwards the owner dogfoods a full day on the new build before phase 3.

## Open questions for the owner (ask only if they block)
- **Week strip:** future days are disabled. Confirm if the owner wants to browse ahead later; that is not needed until the phase 6 schedule work.
