# Handoff: phase-2a-dogfood

**From:** Claude (planner) → **To:** implementer subagent · **Date:** 2026-10-21 · **Branch:** `fix/phase-2a-dogfood`

## Goal
Fix what the first dogfood export showed (session 2, 2026-10-21, 43 pings over one hour):
1. No ping had an activity, because Activity Recognition is registered only in `CaptureCoordinator.begin()`. The permission was granted after the session started, so the whole session ran on distance-only gating. Departures from a stop were then only noticed by the 5 min low-power fix or a late geofence exit: one 1.4 km trip was missed completely.
2. The `GEOFENCE_EXIT` source lands on the next *saved* ping, which can be minutes later and 24 m from the stop (ping 44).
3. Owner tuning: stationary fix interval 5 → 3 min, stay geofence radius 150 → 100 m, and the radius becomes a user setting.

## Constraints
- AGENTS.md hard rules apply. In particular: no Room/schema change (settings are Preferences DataStore), tunables stay in `TrackingConfig`/`Settings` rather than scattered literals, every behaviour change ships with a test, and small diffs.
- Out of scope: segmentation, activity-recognition health/ATTENTION (AR stays optional), and any other settings becoming editable.

## Design decisions (owner-approved)
- **Radius values:** `Settings.stayGeofenceRadiusM: Int = 100`, allowed set `ALLOWED_STAY_RADII_M = setOf(100, 150, 200, 300)`. 100 m is Play's recommended geofence minimum, so nothing smaller is offered.
- **Single source of the radius:** remove `stayGeofenceRadiusM` from `TrackingConfig`. The setting is now the only source, so there are no two competing defaults.
- **When a new radius applies:** at the next STATIONARY entry. The coordinator reads the radius before feeding the machine. A geofence that is already active keeps the radius it was created with, and so does the late-exit safety net for that stop.
- **Exit tag:** `GEOFENCE_EXIT` goes on the first fix delivered after an exit, or on the fix that itself triggered the safety-net exit. If that fix is not saved because the 25 m displacement gate dropped it, the tag is discarded rather than carried forward. A fix rejected for accuracy or speed does not consume the tag.
- **AR retry:** while a session is open and AR is not registered, each reconcile (app open, schedule alarms, health-check reconcile) tries `activityClient.register()` again. On success, the machine switches to activity-aware gating from then on. AR stays optional, so a failed retry is silent.

## Files to touch
- `app/.../tracking/TrackingConfig.kt`:
  - `stationaryFixIntervalMs = 3 * 60_000L`;
  - remove `stayGeofenceRadiusM`.
- `app/.../tracking/CaptureModeMachine.kt`:
  - `activityRecognitionAvailable` becomes settable via `fun onActivityRecognitionAvailable()`;
  - new `var stayRadiusM: Float` (set by the coordinator), with the geofence centre and its radius stored together at STATIONARY entry;
  - the safety-net exit sets `nextSource = GEOFENCE_EXIT`;
  - new `fun discardPendingSource()`, or reuse `takeNextPingSource()`.
- `app/.../tracking/CaptureCoordinator.kt`:
  - inject a new `StayRadiusSource` (`fun interface`, `suspend fun stayRadiusM(): Float`) and set `machine.stayRadiusM` at `begin` and before each `onFixes`/`onActivity` step;
  - in `onFixes`, call `apply(m.onAcceptedFix(fix))` **before** taking the ping source;
  - for `DROP_DISPLACEMENT`, apply and then discard the pending source;
  - track `activityRegistered`;
  - rename `ensureLocationUpdates()` to `ensureCapture()`, which also retries AR registration (update the caller in `TrackingController.kt` and its comment).
- New `app/.../tracking/SettingsStayRadiusSource.kt`: the implementation backed by `SettingsRepository.settings.first()`, bound in `TrackingModule`.
- `core-data/.../settings/Settings.kt` and `SettingsRepository.kt`:
  - new field, allowed set and key `stay_geofence_radius_m`;
  - `setStayGeofenceRadiusM(m)` with `require` in the allowed set;
  - an invalid stored value falls back to the default.
- `app/.../ui/settings/SettingsViewModel.kt` and `SettingsScreen.kt`:
  - the "Stop radius" row is clickable (≥48 dp, `Role.Button`) and shows e.g. "100 m";
  - tapping it opens an `AlertDialog` with one radio option per allowed value: `selectableGroup`, rows `selectable(role = Role.RadioButton)` at ≥48 dp, and a short explanation ("How far you must move from a stop before Tarry starts tracking closely again. Smaller catches short trips sooner but can be triggered by GPS drift indoors.");
  - selecting a value saves it and closes the dialog;
  - put all text in `strings.xml`.
- `docs/specs/phase-2a.md` line 51: note the new defaults (3 min, 100 m, user-configurable).
- Do **not** touch: Room entities/schemas, `:segmentation`, `PingFilter` rules, or other settings rows.

## Tests (required)
- `CaptureModeMachineTest`:
  - the safety-net exit tags the triggering fix's source as GEOFENCE_EXIT;
  - after `onActivityRecognitionAvailable()`, STILL/WALKING transitions take effect (they are ignored before it);
  - a radius change while STATIONARY does not change that stop's safety-net radius, and the next STATIONARY entry uses the new radius (check the `AddStayGeofence` effect).
- `CaptureCoordinatorTest`:
  - geofence exit, then a fix dropped by the displacement gate, then a saved fix: the saved fix is `FUSED`;
  - geofence exit, then an accepted fix: that fix is `GEOFENCE_EXIT`;
  - AR `register()` fails at `begin`, succeeds on `ensureCapture()`: called once more, then not again;
  - the radius from `StayRadiusSource` reaches the `AddStayGeofence` call.
- `SettingsRepositoryTest`: default 100, round-trip, invalid stored value falls back, setter rejects 50.
- A Settings UI test (Robolectric Compose, as in `app/src/test/.../ui/`): the row shows the value, the dialog lists the 4 options, and selecting one calls the callback.
- Update existing tests that referenced `TrackingConfig.stayGeofenceRadiusM` or `ensureLocationUpdates`.

## Verification commands
```
./gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug
```
(Git Bash; run from the repo root.) Everything must be green with no `@Ignore`.

## Known risks / questions
- `TrackingReceiversTest` uses fragile reflection; do not touch it unless it breaks.
- Commit on `fix/phase-2a-dogfood` with conventional messages ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not commit `.idea/*`, `.gitignore` or `tmp/`.
