# Handoff: phase 8a.2 — Tracking health screen + Test tracking

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-10 · **Branch:** given in your prompt (never main). Do this **after** `phase-8a2-onboarding.md` (it reuses `rememberPermissionsActions`).

Read first: `AGENTS.md` (rules 2, 5, 6, 11), `docs/design/ui-rules.md`, `docs/handoffs/phase-8-launch.md` § 2026-11-10.

## Owner decision
"Tracking health" **replaces** the Permissions row in Settings → Tracking. The Permissions screen stays, reached from the health screen and from the attention sheet.

## Build
1. **Route + row.** `TrackingHealthRoute` in `ui/navigation/Routes.kt`. In `ui/settings/SettingsScreen.kt` (~355) replace the Permissions row with "Tracking health" (icon: reuse `ic_permissions`, or a Material Symbols Rounded 300 `health_and_safety` in the same import format as other `ic_*`), subtitle = current state ("Working" / "Needs attention" / "Off" style, derived from `TrackingState`). Rename the threaded callback `onOpenPermissions` -> `onOpenTrackingHealth` (~102/147/185 and `TarryApp.kt`). The attention sheet fix buttons (`ui/shell/TrackingSheet.kt` `fix()`) keep opening Permissions.
2. **`ui/health/`**: `TrackingHealthScreen` (Hilt VM) + stateless `TrackingHealthContent`.
   - **Status card** from `TrackingStateRepository.state`/`details`; map `attentionCause` with the existing `messageRes()` in `TrackingSheet.kt` (share it, don't duplicate strings).
   - **Checks** (`SettingsGroup` rows, ok/attention icon plus a text state, never colour alone): precise location, background location ("Allow all the time"), location services on (`PreconditionChecker`), physical activity, notifications (only when applicable), exact alarms, battery optimisation (unrestricted). Each not-ok row taps to its fix through the existing `PermissionIntents` / `changeIntentFor()` / runtime launchers (`rememberPermissionsActions`); runtime ones may simply open the Permissions screen if that is cleaner. Re-check on `ON_RESUME`.
   - **"Why tracking can stop"**: 3–4 short sentences (battery saver or "Restricted" battery use, force stop or swiping the app away on some phones, a permission turned off, location services off).
   - **Test tracking**: `SecondaryButton` "Test tracking". Runs one fix:
     - Add to `LocationClient` (`tracking/PlatformClients.kt`) `suspend fun currentFix(priority: LocationPriority, timeoutMs: Long): CurrentFixResult` (Fix / Timeout / Denied). `PlayLocationClient` uses `FusedLocationProviderClient.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(..).setMaxUpdateAgeMillis(0).setDurationMillis(timeoutMs).build(), token)`, awaits it, cancels the token on coroutine cancellation; `SecurityException` -> Denied, never a crash.
     - `TrackingConfig.testFixTimeoutMs = 30_000L` (KDoc). Use `movingPriority` (high accuracy).
     - Result card, announced with `liveRegion = Polite`: success -> "Got a fix: ±12 m, 3 s old" plus "Tracking can start" or the first failing precondition / health cause text; timeout -> "No fix in 30 s. Go outside or near a window and try again."; denied -> "Location permission is off." with a Fix action.
     - No session, no ping, no repeat (rule 5: one user-initiated request). Button disabled while running, with progress text.
3. Strings in `strings_health.xml`, sentence case.

## Tests
- `FakeLocationClient.currentFix` (fix / timeout / denied).
- VM: checks mapped from a fake `PermissionChecker` (add a simple fake) + `FakePreconditions`; test-result states; button disabled while running.
- Compose (font 1.0 and 2.0): rows show text state; not-ok rows are clickable with a role; result card text; the Settings row is replaced (Settings UI tests), shell push (`TarryAppShellTest`).

## Rules
No new network or SDK. No Room schema change. Small diffs. `gradlew.bat testDebugUnitTest lintDebug assembleDebug` green. Commits by item, explicit paths (include this handoff in the first), never `.idea/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`. Report files, tests, decisions, skips.
