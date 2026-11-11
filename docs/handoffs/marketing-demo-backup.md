# Handoff: demo dataset for marketing screenshots (step 1 of `marketing-screenshots.md`)

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-11 · **Branch:** `feat/marketing-screenshots` (never main).
Read first: `AGENTS.md`, `docs/handoffs/marketing-screenshots.md` (Principles + Step 1), `app/.../export/backup/` (BackupCodec,
BackupManager, BackupModels, BackupPrefs).

## Goal
A JVM test-source utility that writes a valid Tarry backup file (`tarry-backup`, `CURRENT_VERSION`) with about two weeks of
an invented contractor's work, so Settings → Restore on the **emulator** loads it. No app feature code changes.

## Decisions (planner)
- **Area: inner Melbourne** (Richmond / Collingwood / Fitzroy / Abbotsford / Brunswick), not Sydney: the owner's own data is
  in Sydney. Coordinates may sit on real streets (map tiles are real) but every **name is invented** and no note contains a
  real address. Time zone `Australia/Melbourne`.
- **Invented places** (use these): "Harbourside Apartments" is Sydney-flavoured, so instead: "Yarra Bend Apartments",
  "Collingwood warehouse fit-out", "Northside depot", "Café on Smith", "Richmond terrace reno", "Supplier yard". Place radii 50–120 m.
- **Invented jobs/clients** with distinct `JobPalette` colours and billable defaults, e.g. "Yarra Bend – Stage 2" (billable),
  "Collingwood fit-out" (billable), "Terrace reno" (billable), "Admin & supplies" (not billable).
- **Days:** 10 working days ending on the **day passed in** (default: today in Australia/Melbourne), Mon–Fri, 7:00–17:00
  with a shorter Friday (7:00–14:00) as the schedule template. Every day's activity ends before 16:30 so captures in the
  evening show a complete "today".
- **Content mix:** each day 3–6 visits and the trips between them (pings dense enough for route lines: ~every 30 s while
  moving, every 2 min while stopped, realistic accuracy 5–15 m, activity types set); most visits labelled with a job via
  job blocks; **2–3 "Needs review"** visits in the last three days; a few notes ("Measured kitchen", "Waiting on tiles");
  **one honest gap** (a 20–30 min NO_FIX, e.g. in a basement car park) and **one interrupted session** (KILLED, restart
  over 5 min later) somewhere in week one, not on "today".
- **Check whether restore re-runs segmentation.** If it does, write pings + sessions and let segmentation produce visits
  and trips (then labels must be attached in a way that survives: job blocks by time range, notes on frozen/edited visits —
  find what the app does and pick the robust path; describe it in your report). If it doesn't, write segmented rows
  consistent with the pings. Either way the restored timeline must look right; verify by a Robolectric test that restores
  the generated file through `BackupManager` into an in-memory DB and asserts: 10 days of sessions, visits on "today",
  2–3 needs-review visits, ≥1 job block per day, exactly one KILLED session, one NO_FIX gap.
- Use the real model/entity classes and the codec so the format can't drift. Reuse segmentation test helpers for traces if
  simpler.

## How it runs
- A test class gated like the replay tests: it only generates when `-Ptarry.demo` is set (value: an ISO date for "today", or
  `today`), otherwise it is skipped. Output: `app/build/demo/tarry-demo.json` (git-ignored by being under build/).
- Wire the property in `app/build.gradle.kts` next to `tarry.replay` (systemProperty), same pattern.
- The verification test above runs in the normal suite (it generates into a temp dir with a fixed date), so CI covers it.

## Rules
No Room schema change, no segmentation change, no main-source change unless the backup API truly lacks something (then
stop and report). No real names, addresses or anything from `tmp/`. Small diffs. `./gradlew.bat testDebugUnitTest
:segmentation:test lintDebug assembleDebug` green, and also run the generator once (`-Ptarry.demo=today`) and report the
file path and size. Commits explicit paths (include this handoff), never `.idea/` or `.claude/`, each ending with exactly
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push. No adb, no device. Report files, tests, decisions
(especially the segmentation-on-restore finding), skips.
