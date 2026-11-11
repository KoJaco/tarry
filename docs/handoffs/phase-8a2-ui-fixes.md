# Handoff: phase 8a.2 — emulator UI review fixes

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-10 · **Branch:** `feat/phase-8a2` (checked out; never main). Apply after `phase-8a2-review-fixes.md` is committed.

Emulator review screenshots are in `tmp/ui-review-8a2/` (local only). Read `docs/design/ui-rules.md` first. Each fix gets a Robolectric test where behaviour or layout changes (font 1.0 and 2.0).

## Fix
1. **Onboarding back is dead.** Arriving on a page whose permission is already granted auto-advances, so Back bounces forward again (`OnboardingScreen.kt` ~30, `OnboardingContent.kt` ~86, `OnboardingViewModel.kt` ~60). Auto-advance only on a *transition* to granted while the user is on that page (dialog result or `ON_RESUME` after visiting settings), never on arrival; and Back skips over pages whose permission is granted, landing on the previous non-granted page or Welcome. Test: grant on page 3, go to 4, Back → page 2 (not 3), Back → Welcome.
2. **"Not now" on Precise location** must not skip the rest: it skips the remaining permission pages and goes to the Schedule page, then Done (user still sets hours). Update the button's content description accordingly. Test.
3. **Done page when tracking can't run.** If required permissions are missing (`PermissionsUiState.missingRequired` / `canTrack == false`), the Done page replaces "Tarry starts and stops on its own" with a warning line: "Tracking can't start until location is allowed all the time. Fix it in Settings → Tracking health." (wording may be tightened; keep it true). Heading: "You're set" when tracking can run, "Almost there" otherwise; the "Tracking now" / "Next: Wed 7:00 am" line becomes body text under the heading. Tests for both variants.
4. **Empty today while tracking needs attention.** Add `DayTrackingReason.NEEDS_ATTENTION`: today, inside an active interval, and `TrackingStateRepository.state` is `ATTENTION` → reason line "Not tracking: needs attention" (tappable to open Tracking health is nice-to-have; only if trivial). Inject the state flow into `TimelineViewModel` via the existing pattern (internal-ctor lambda/flow for tests). Test VM + Compose.
5. **Test tracking result off-screen.** When the result card appears, bring it into view (`BringIntoViewRequester` or scroll the column to it), above the bottom bar at font 2.0. Add a leading ok/attention icon to the result card (success vs timeout/denied/blocked), text still carries the meaning. Test that the card is displayed after a result at font 2.0.
6. **Tracking pill wraps "Needs attention" at 1.0x** in the bottom bar. Keep the pill on one line: use a shorter visible label "Attention" for the ATTENTION state (content description stays "Tracking: needs attention"), or the existing pill sizing fix if one fits better; never ellipsise. Test at 1.0 and 2.0 that the label is one line and not truncated.
7. **Onboarding schedule grid** is capped to ~3 hours with empty space below: let it fill the available height (weight within the page column, bounded), keeping the primary button pinned. Test height > a few rows at a 411x891 window.
8. **Health status card inset**: align the body text with the title (same start as the title text, after the icon), per the screenshot `18-health-light.png`.

9. **Midnight mirror of N2.** A kill at 23:58 with a restart at 00:01 still shows a stray "Tracking started" at 00:01 on the next day. When building markers, also load sessions ending up to `shortInterruptionMs` before the day's start, and drop a start that cancels a short KILLED stop from the previous day. Test.

## Leave (list in your report only)
Week strip at 2.0x (pre-existing), onboarding "If you skip" copy, Settings subtitle naming the cause, empty-day reason colour.

## Rules
No Room schema change. Small diffs. `gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. Commits by item, explicit paths (include this handoff in the first), never `.idea/` or `.claude/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push. No adb, no device installs, no `connectedDebugAndroidTest`. Report files, tests, decisions, skips.
