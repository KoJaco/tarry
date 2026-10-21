# Handoff: phase-3-timeline-editing

**From:** Claude (planner, previous session) → **To:** a fresh Claude Code session (planner), running the implementer, test-writer, migration-guard (only if needed), ui-reviewer and reviewer subagents · **Date:** 2026-10-21 · **Branch:** `feat/phase-3-timeline-editing`, from `main` after the dogfood day

## Start here
1. Read `AGENTS.md`, `CLAUDE.md`, then **`docs/specs/phase-3.md`** (the tasks) and `docs/design/tokens.md` (every visual value). This handoff only adds context and gotchas.
2. Ask the owner the two ⚑ questions in the spec's Decisions (inline job creation; the manual-visit entry point), plus anything the dogfood day surfaced. Don't start T1 before that.
3. `git switch main && git pull && git switch -c feat/phase-3-timeline-editing`.
4. Work through T1 → T6. Commit per task, and run the reviewer before opening the PR.

## Where the project is (2026-10-21)
- **Merged:**
  - phases 0, 1, 2a (#4, #5) and 2b (#6);
  - the 2c docs (#7) and **2c design system (#8)**.
- **Pending on the owner:** a full dogfood day on the 2c build (installed on the Pixel 2026-10-21 evening), then phase 3.
- **Test counts on main:** app 493, core-data 62, segmentation 103. All green, with no `@Ignore`.
- **Room schema:** v1 (`core-data/schemas/.../1.json`). It already has everything phase 3 needs (see the spec).

## What 2c left you (use it, don't rebuild it)
- **Theme** `me.tarry.app.ui.theme`:
  - `TarryTheme.colors`, `.space` (xs4 s8 m12 l16 xl20 xxl24 xxxl32 huge48 gutter20) and `.shapes` (pill, card 20, container 24, block 12, small 8), plus `TarryTheme.isDark`;
  - `JobPalette.lookup(colorArgb, jobId)`, with 8 slots and a deterministic fallback;
  - fonts are Nunito (headings) and Nunito Sans (body), and a test shows their default digits are tabular;
  - no `Color(0x` outside `ui/theme`, enforced by `NoColorLiteralsTest`.
- **Components** `ui/components`: `TarryCard`, `SectionHeader`, `SettingsGroup { row(…) }` (**the default for grouped rows**: one surface, full-width `divider` lines), `SettingsRow`/`SettingsValueRow`/`SettingsSwitchRow`, the Primary/Secondary/Tertiary/Destructive buttons, `TarryChip`, `SegmentedControl`, `IconCircleButton`, `EmptyState`, `LoadingBlocks`, `ErrorCard`, `rememberReducedMotion()` and `@TarryPreviews`.
- **Timeline** `ui/timeline`:
  - `DayLayout.layoutDay(...)` is pure and heavily tested. It handles DST, push-down with `itemGapDp`, folded trips, and inner gaps (outages inside a visit become `PlacedBlock.innerGaps`, drawn as a small red dot on the block's left border plus an "Includes N min not tracked" line). Its `nowDp` follows the placed blocks.
  - `DayCanvas`: blocks are merged semantics nodes in time order (`traversalIndex`).
  - `TimelineText` holds the formatters, which honour the 12/24 h setting, and the spoken descriptions.
  - `TimelineViewModel` exposes `jobId`, `jobName` and `jobColorArgb` on visits.
- **Shell:** `TarryBottomBar` (pill backing that glides, haptic tap, no ripple) and the four-state `TrackingToggle`.

## Design decisions already made (do not re-ask)
- **Owner:**
  - the calendar canvas is the timeline;
  - the accent (apricot) is only for tracking state and needs-you cues, and the nav pill uses `accentSoft`;
  - grouped surfaces with dividers (not separate cards) for lists of rows;
  - outages inside a visit show as a border dot;
  - the date picker has no text-input mode.
- **Reference images** are in `tmp/design-inspiration/` and `tmp/design-inspiration/minimalist-design-goal/`, which is gitignored and never committed. Especially:
  - `surface-segregation-example.png` and `settings-and-lower-nav-example.png` for rows;
  - `overlay-on-map-container-example.png` for sheets over content. The drawer should feel like that sheet: a handle, a big title, a muted subtitle, then grouped rows.

## Constraints and project rules that bite
- Never commit to `main`; use one branch per phase.
- **Commit and PR footers:** commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, and PR bodies end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- **Real location data never enters the repo:** no traces and no screenshots of real days. `tmp/` holds the owner's exports. If you need the device DB to diagnose something, copy it only into the session scratchpad and delete it afterwards.
- Rule 3: frozen records are never touched by segmentation. Rule 4: any schema change bumps the version and ships a migration with a test. Rule 7: every behaviour change ships with a test, and segmentation interactions need a golden case.

## Environment gotchas (Windows 11, Git Bash)
- **Build and install:**
  - build: `./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` (about 3–5 min);
  - install: `adb -s 57151FDCR0075V install -r app/build/outputs/apk/debug/app-debug.apk`. **Never uninstall**: the owner's data lives in the app.
  - **Each install kills the app**, so capture records a "Not tracked · App was closed" gap at that time. That's expected, not a bug.
- **Screenshots:** `adb exec-out screencap -p > <scratchpad>/x.png`, then Read it. Wake the screen first with `adb shell input keyevent KEYCODE_WAKEUP`.
- **Tools:** no Python. `sqlite3` comes with the platform-tools; use awk for quick maths.
- **File editing:** many files are CRLF, so prefer the Edit tool. `perl -0pi` needs `\r?\n`.
- **Parallel subagents in one worktree:**
  - give each its own files and have it stage by explicit path, never `-A`;
  - a shared `strings.xml` is a conflict risk, so use per-feature `strings_<feature>.xml`, as 2c did with `strings_timeline.xml`;
  - concurrent Gradle runs overwrite each other's test reports.
- **Flaky-test lesson from 2c:** a test that leaves WorkManager work running can leak an exception into a *later* Compose test (`UncaughtExceptionsBeforeTest`). Cancel work in `@After`.
- **Compose tests:** they run on Robolectric. Text-fit assertions need `@GraphicsMode(NATIVE)` plus the shared `assertNotTruncated` helper in `TextAsserts.kt`.

## Suggested sequencing
- **T1** first. It is pure data and blocks everything else.
- **T2 and T3** can then run in parallel, with the drawer in `ui/drawer/` and the canvas wiring in `ui/timeline/`; agree the `onOpenItem(id, type)` callback first.
- **T4 and T5** come after T2.
- **Taste check:** after T2, install the build and let the owner try the drawer on a real visit before T4.

## Definition of done
Everything in `docs/specs/phase-3.md` § Acceptance criteria, then a PR to `main` with green CI. The owner then labels a real day with it.

## Follow-ups noted in 2c (not phase 3 unless the owner asks)
- **Toggle:** the "Paused · 45 m" time left needs the pause end plumbed into `TrackingState`.
- **Day canvas:** the `layoutDay` `window` parameter (the tracking window) is unplumbed until phase 6.
- **Toggle label:** it shrinks rather than splits a word at font scale ≥ 2 on narrow screens. Revisit if the owner finds it too small.
- **Device checks:** TalkBack, font scale 2.0 and the battery "Change" button have not had a formal pass on the device yet.
