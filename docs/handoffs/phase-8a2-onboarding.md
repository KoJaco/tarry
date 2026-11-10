# Handoff: phase 8a.2 — first-run onboarding

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-10 · **Branch:** given in your prompt (never main).

Read first: `AGENTS.md` (rules 6, 8, 11), `docs/design/ui-rules.md`, `docs/design/tokens.md`, `docs/brand/brand-and-copy.md` (lines ~68-106), `docs/handoffs/phase-8-launch.md` § 2026-11-09 and § 2026-11-10, `docs/decisions/0003-background-start.md`.

## Owner decisions
- Fresh installs see onboarding. Existing installs skip it **silently** when fine location is already granted **or** any tracking session exists.
- `onboarding_completed_at` (epoch ms) is the flag; 8b's trial clock will start from it. Delete-all keeps it (already the rule).
- Done saves the schedule and lets the normal reconcile start tracking if inside a window now.

## Build
1. **Flag + query.**
   - `SettingsRepository` (`core-data/.../settings/SettingsRepository.kt`): `onboardingCompletedAtMs(): Long?` and `setOnboardingCompletedAtMs(Long?)`, following `PRUNED_BEFORE_MS` (Keys object, `catch { IOException -> emptyPreferences() }`). Key name constant next to `PRUNED_BEFORE_KEY` in `Settings.kt`. Check `BackupStore.deleteAll`/restore keep it (they keep settings; add a test if the key list is explicit).
   - `TrackingSessionDao.any(): Boolean` = `SELECT EXISTS(SELECT 1 FROM <session table>)` (check the real table name), exposed via `TrackingRepository`. Query only, no schema change.
2. **Routing** (`ui/TarryApp.kt` ~105-119, `ui/shell/ShellViewModel.kt:40`): replace `needsInitialPermissions()` with `suspend fun needsOnboarding(): Boolean`: flag set -> false; else if `permissions.check().fineLocation || trackingRepo.anySession()` -> set flag to now (injected clock) and return false; else true. On true push `OnboardingRoute` (new `@Serializable data object` in `ui/navigation/Routes.kt` with KDoc). Remove the old first-launch Permissions push. Keep the `rememberSaveable` guard so it runs once per process.
3. **Full screen.** `TarryAppContent` (~256-321) always draws the bottom bar; add a no-chrome branch so `OnboardingRoute` renders without the bottom bar and tracking pill. Back: on the first page, system back finishes the activity (default); on later pages, back goes to the previous page (`BackHandler`).
4. **`ui/onboarding/`**: `OnboardingScreen` (Hilt VM) + stateless `OnboardingContent` (testable). Pages:
   1. **Welcome**: the logo (`R.drawable.tarry_logo`, decorative), headline "Your work day, already written down.", a short body from the brand doc, and the privacy promise ("No account. No cloud. Your location stays on your phone unless you export it."). Primary "Get started".
   2. **Permissions**, one page per *applicable* `PermissionStep` not already granted (granted ones are skipped), in enum order:
      - Reuse `PermissionsViewModel` + `buildPermissionsUiState`. Extract the launcher/intent block from `PermissionsScreen.kt` (~27-95) into `rememberPermissionsActions(viewModel, ...)` used by both screens; the existing Permissions screen must behave exactly as before (`PermissionsUiTest` stays green).
      - Make `PermissionRow` and the `iconRes/titleRes/bodyRes/degradedRes` helpers `internal`, or add an `internal fun PermissionStepPage(item, actions)` that reuses them. A page = icon, title, body, primary "Allow" (or "Open settings" for intent steps), and for skippable steps a `TertiaryButton` "Skip".
      - BACKGROUND_LOCATION shows `BackgroundDisclosureDialog` first (prominent disclosure), as today.
      - LOCATION is not skippable; a `TertiaryButton` "Not now" finishes onboarding (sets the flag) so the existing ATTENTION path shows (rule 6). Refresh on `ON_RESUME` and advance when the permission came back granted.
   3. **Schedule**: title "When do you work?", body "Paint your hours once. Tarry starts and stops on its own." The stateless `ScheduleGrid` (`ui/schedule/ScheduleGrid.kt`, needs a bounded height) seeded with `Schedule.MON_FRI_7_TO_17`, editable (toggle/stroke). Primary "Save hours". **Always** write the template; do not go through `ScheduleViewModel.save()` (its `dirty` check skips the preset on fresh installs). Add a small injectable saver/use-case that runs the same chain as `ScheduleViewModel.apply` (~173-197): `controller.updateSchedule(ScheduleChange.SaveTemplate(s))` -> `trackingState.refresh()` -> `reviewReminder.rearm()` on the capture scope; have `ScheduleViewModel` use it too if that is a clean, small change.
   4. **Done**: set the flag (now), call the controller's reconcile with the UI source (check the real API), then show "Tracking now" if inside a window, else "Next: %s" (weekday + time, shared formatters; find the next window with `ScheduleWindowChecker`/`EffectiveScheduleCalculator`, reuse, don't re-derive). Primary "Open Tarry" pops onboarding to the Timeline.
   - Layout: `TarryTheme` tokens, gutter, 48 dp targets, a page indicator ("Step 2 of 6" text is fine and accessible); content scrolls at font 2.0 with the primary button always reachable.
5. **Strings** in a new `strings_onboarding.xml`, sentence case, no exclamation marks.

## Tests
- Routing (`ShellViewModel`): fresh -> onboarding; fine granted -> no onboarding and flag set; session exists -> no onboarding and flag set; flag set -> no onboarding.
- `OnboardingContent` Robolectric (font 1.0 and 2.0): page order (granted steps skipped); Skip advances; "Not now" on location finishes and sets the flag; the schedule page saves the preset even unchanged (fake saver); Done shows "Tracking now" / "Next: ..."; back goes to the previous page. `TarryAppShellTest`: no bottom bar on the onboarding route.
- `SettingsRepositoryTest` for the new key; DAO test for `any()`.
- `PermissionsUiTest` unchanged and green.

## Rules
Rule 8 applies (permissions): stay within this handoff. No Room schema change. Small diffs. `gradlew.bat testDebugUnitTest lintDebug assembleDebug` green. Commits by item, explicit paths (include this handoff in the first), never `.idea/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`. Report files, tests, decisions, skips.
