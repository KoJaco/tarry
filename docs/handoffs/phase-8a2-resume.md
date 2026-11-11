# Handoff: phase 8a.2 — resume point (paused 2026-11-10 evening)

**Branch:** `feat/phase-8a2` (pushed; never main). Plan: `~/.claude/plans/we-are-starting-on-synchronous-cherny.md`. Decisions: `phase-8-launch.md` § 2026-11-10.

## Done on the branch
All four parts (killed-session fix, empty-day reason, onboarding, Tracking health + Test tracking), the first code review's fixes (`phase-8a2-review-fixes.md`) and the emulator UI review's fixes (`phase-8a2-ui-fixes.md`). Full `testDebugUnitTest :segmentation:test lintDebug assembleDebug` was green after the UI fixes. The track A worktree `.claude/worktrees/phase-8a2-a` is merged and can be removed.

## Next, in order
1. **Re-review fixes (must-fix before PR), each with a test:**
   - **S1 — health worker must still restart a dead service inside the 5-min grace.** `HealthEvaluator.kt:63-66` + `HealthChecker.kt:91-95`: when `shouldRun && !serviceRunning` but the outage is still under `shortInterruptionMs`, return null so `check()` never calls `controller.reconcile(SYSTEM)`. Keep reconciling (restart attempt) but record no Gap and raise no alert (e.g. `SERVICE_ISSUE` with `recordGap = false` and no notification, or a separate "restart needed" branch). Extend `aShortRestartBeforeTheWorkerShowsNothing` to assert a reconcile happened.
   - **S2 — onboarding forward onto an already-granted permission page is a dead end.** `OnboardingViewModel.next()` (~73) must skip granted permission pages, mirroring `previousIndex()` in `OnboardingState.kt:56`. Add a test that covers the transition-only auto-advance in `OnboardingScreen.kt:34-39` (render the screen or move the logic into a testable unit).
   - Nits (optional): N2 onboarding template read on `viewModelScope` instead of `captureScope`; N3 brace indent `OnboardingContent.kt:130`.
   - Noted, not fixing: N1 the NEEDS_ATTENTION / NOT_YET reason doesn't recompute when a window opens or closes while the Timeline stays open (no tick by design). Also: today after the window ended with nothing recorded shows no reason line (NONE by design).
2. **Emulator kill/restart check** (not yet done; tracking never ran in the UI review). Recipe that works: `adb -s emulator-5554` only; fresh install; `pm grant` fine/coarse/background location, activity recognition, notifications; `appops set me.tarry.app SCHEDULE_EXACT_ALARM allow`; `emu geo fix 151.2093 -33.8688`; tap the pill → "For 1 hour"; feed fixes for a couple of minutes; then `am crash me.tarry.app` (or `am kill` with the app in the background) and relaunch within 5 min → Timeline shows no marker/hatch/Gap; repeat with > 5 min → "Interrupted" marker, Gap from the real death time, no hatch over a continuing visit. Note: a fresh install with fine location pre-granted skips onboarding (expected).
3. **Pixel** (was disconnected at pause): back up DB + datastore to `tmp/` first (`phase-8-start.md` "Working rules"), install, check the upgrade skips onboarding, Tracking health shows real status, Test tracking works; the #33 reminder check if convenient. Never confirm destructive actions on the phone; never swipe the timeline canvas.
4. Full verification, push, open the PR (`🤖 Generated with [Claude Code](https://claude.com/claude-code)` footer), CI green, owner merges.
5. Then **8a.3** (`phase-8a3-map-strip-and-time-text.md`: Map tab day strip, sized stops, item card; compact timeline time text), on its own branch from main.
6. Then the marketing and Play screenshots (`marketing-screenshots.md`) once what ships is settled, and 8b (billing) planning.
