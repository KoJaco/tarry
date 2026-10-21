# Phase 2a — Capture

Branch: `feat/phase-2a-capture`. Size: L. Parent: `docs/specs/build-plan.md` (phase 2a). Source spec: `docs/specs/tarry-mvp.md` §Tracking engine, §Permissions. Follows phase 1 (PR #3).

## Goal
Make the phone record location on its own during scheduled hours. A foreground service runs only inside active windows. Capture mode (STATIONARY/MOVING) follows motion. Filtered pings and sessions go into Room. The bottom-bar toggle shows the real state, and outages surface as ATTENTION plus Gap rows. At the end of this phase the owner installs the app and carries it on real days. Their raw-ping exports feed phase 2b.

## Non-goals
- Segmentation, places and visits (2b).
- The schedule grid UI (phase 6). The schedule comes from the stored template, seeded with the Mon–Fri 07:00–17:00 preset.
- Full onboarding (phase 8).
- Theme tokens (phase 3).
- Retention purge (phase 8).
- Maps.

## Carry-over from phase 1 (read before starting)
- **Toolchain** (ADR 0002):
  - The compiler is Kotlin 2.2.10, bundled with AGP 9.4.1. Don't add libraries that need newer Kotlin metadata. `kotlinx-serialization-json` is capped at 1.9.0 for this reason.
  - Use KSP only, no kapt.
- **Navigation 3:** routes are `@Serializable` `NavKey` objects in `app/.../ui/navigation/Routes.kt`. `TarryAppContent` is the stateless shell, so it can be tested without Hilt.
- **Tracking state:** `TrackingStateRepository` (`app/.../tracking/`) currently binds `IdleTrackingStateRepository` in `di/AppModule.kt`. This phase replaces it.
- **Data access:** use `TrackingRepository`, `ScheduleRepository` and `TimelineRepository` (`core-data/.../repository/Repositories.kt`). The `save*` methods return the real id on update, even though Room's raw upsert returns -1 in that case.
- **WorkManager:** `hilt-work` is on the classpath but not wired. The first `@HiltWorker` (T8) must add a `Configuration.Provider` on `TarryApplication` with `HiltWorkerFactory`, and remove the default `WorkManagerInitializer` via a manifest `tools:node="remove"` on the startup provider entry.
- **Robolectric:** tests need `@Config(sdk = [35])` and the `--add-opens` jvmArgs that are already in both Android modules.
- **Schema:** the Room schema is v1. **This phase should need no schema change**, because tracking runtime status lives in DataStore. If one turns out to be necessary, stop and plan it: version 2, a migration and a `MigrationTestHelper` test, using `migration-guard` (rule 4).
- **Device:** the only test device is the owner's Pixel 10 (API 37).

## Design notes
- **Single entry point:** `TrackingController` (`app/.../tracking/`) is the only thing that starts or stops capture. Alarms, receivers, the toggle, notification actions and the health worker all call it. Rules 5 and 6 then hold in one place.
- **Runtime status:** `TrackingStatusStore` is a second Preferences DataStore file, `tracking_status`, separate from settings. It holds the heartbeat (last fix time), the current capture mode, the active session id, the last ATTENTION cause, and the open health Gap id.
- **Tunables:** every one lives in `TrackingConfig` (a data class with defaults from the MVP spec tables, provided by Hilt). There are no literals in service or engine code (rule 5).
- **Pure logic:** keep the capture-mode machine, ping filter, effective-schedule calculator and state reducer as pure Kotlin classes in `:app` with no Android types in their signatures, so they get plain JVM tests.
- **Time and zones:** use `ZoneId.systemDefault()` at capture for every row's `zoneId`. Compute schedule transitions with `ZonedDateTime` so DST shifts are handled.

## Tasks (in order)

### T1 — Background start spike (claude, S). Do it first, and stop if it fails.
Prove on the Pixel 10 (target 37) that an alarm can start a `location`-type foreground service with the app swiped away, the screen off, and the device in forced Doze.
- **Alarm:** `setExactAndAllowWhileIdle` with `SCHEDULE_EXACT_ALARM` granted. Check `canScheduleExactAlarms()`, and grant through `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
- **Location permissions:** with fine and background location granted, check whether background location also lifts the while-in-use restriction on location FGS.
- **Service start:** `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)`.
- **What to record:**
  - Whether the start succeeds.
  - Whether a fix arrives within 2 minutes.
  - What happens when exact alarms are revoked: an inexact `setWindow` should then fail to start the FGS. Confirm that.
- **Outcome:** write it up in `docs/decisions/0003-background-start.md`. If the alarm start is blocked, the fallback is a high-priority "Tap to start tracking" notification, with the health state flagged. Get the owner's sign-off before building on the fallback.
- Use a throwaway commit plus a revert, as phase 1 did.

### T2 — TrackingConfig and status store (implementer, S)
- `TrackingConfig` holds:
  - Capture modes: the STATIONARY entry rule (50 m / 3 min), the stationary fix interval (5 min, passive/low power) and the geofence radius (150 m). MOVING uses balanced power, 20 s interval, 25 m minimum displacement and a 2 min maxUpdateDelay.
  - Filters: drop fixes with accuracy worse than 100 m, and drop fixes implying more than 70 m/s.
  - Health: no-heartbeat threshold 20 min, worker period 15 min.
- `TrackingStatusStore` (DataStore) with typed accessors and a `Flow`.

### T3 — Pure capture logic (implementer, M)
- `PingFilter`: implements the three filter rules from the MVP spec, including STATIONARY jitter (drop fixes inside the current accuracy circle). It is stateful over the last accepted fix.
- `CaptureModeMachine`:
  - Inputs are activity transitions, geofence exit and accepted fixes.
  - Outputs are the mode plus side-effect intents (request location with params X, add the stay geofence at a point, remove the geofence).
  - It falls back to distance-only gating when activity recognition is unavailable.

### T4 — Platform clients (claude, M)
Thin interfaces with Play Services implementations:
- `LocationClient` (fused, with priority/interval/displacement/batching from the machine's intents);
- `ActivityTransitionClient` (Transition API, `PendingIntent` receiver);
- `GeofenceClient` (one stay geofence).

Use the `PendingIntent` mutability flags that API 31+ requires, and keep receivers non-exported. Fakes for tests.

### T5 — TrackingService (claude, L)
- **Notification:** a foreground service of type `location`, on a low-importance ongoing notification channel. The notification shows the state and has actions **Pause 1 h** and **Stop**, both routed to `TrackingController`.
- **On start:** open a `TrackingSession` (trigger SCHEDULE or MANUAL, `zoneId`), start in MOVING (so the first fix is quick), and write a `MANUAL_START` ping for manual starts.
- **Pings:** filter each fix and save the accepted ones with `zoneId`, the current activity and `source`. After a geofence exit, the first ping is `GEOFENCE_EXIT`. Update the heartbeat on every fix or batch.
- **On stop:** close the session with the right `endReason`, remove the geofence and location updates, and clear the status.
- **Crash recovery:** if an open session exists when the service starts (or when the controller reconciles), close it at its last ping time with `KILLED`, and write a `Gap` (`SERVICE_KILLED`) from there to now if that span is inside a window.

### T6 — Schedule engine (claude, M)
- **First launch:** if there is no template, seed `Schedule.MON_FRI_7_TO_17`.
- **`EffectiveScheduleCalculator` (pure):**
  - Inputs: the template, the override for the current week, active `ManualOverride`s, the instant and the zone.
  - Outputs: `isActive`, `nextTransition: Instant?` and the current window bounds.
  - Layering: a week override replaces the template, and manual START/PAUSE apply on top. Manual end modes: UNTIL_STOPPED, END_OF_DAY, DURATION and NEXT_WINDOW.
- **`ScheduleAlarmScheduler`:** arms exactly one alarm for the next transition. It uses an exact alarm if allowed, otherwise inexact plus a degraded flag that surfaces as ATTENTION when a window is missed.
- **On a window edge:** call `TrackingController.reconcile()`. It starts or stops the service to match the effective schedule, then re-arms the alarm. At a window end it also calls a `SegmentationTrigger` interface, which is a no-op in 2a and gets implemented in 2b.
- **Receivers that re-arm:** `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` and `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. App start also reconciles.
- **Debug builds only:** a "test window" (now + 2 min, lasting 10 min), stored in the status store and layered in by the calculator, so device tests don't have to wait for 07:00.

### T7 — Manual controls (implementer, M)
- **`TrackingController` API:**
  - `start(endMode, durationMin?)`;
  - `pause(PAUSE_30M | PAUSE_1H | UNTIL_NEXT_WINDOW)`;
  - `resume()`;
  - `stop()`, which ends the session; tracking resumes at the next window.
- It persists `ManualOverride` rows (deactivating them when they end) and re-arms the alarm.
- **Toggle tap** opens a minimal bottom sheet that depends on the state:
  - IDLE: Start, with until stopped, end of day, or 1/2/4 h.
  - TRACKING: Pause 30 min, Pause 1 h, Pause until next window, or Stop.
  - PAUSED: Resume, with the resume time shown.
  - ATTENTION: the cause plus a fix action (open the permission screen or system settings).
- Phase 6 polishes this sheet. Strings go in resources, and touch targets are at least 48dp.

### T8 — Health monitor and real tracking state (claude, M)
- **`HealthCheckWorker`:** a `@HiltWorker`, periodic every 15 min. Wire `HiltWorkerFactory` as described in the carry-over. When inside a window with no heartbeat for more than 20 min, or with a broken precondition, it:
  - sets ATTENTION with a cause (`AttentionCause`: NO_FINE_LOCATION, NO_BACKGROUND_LOCATION, LOCATION_OFF, SERVICE_NOT_RUNNING, NO_FIX_WHILE_MOVING, EXACT_ALARM_DENIED);
  - tries `reconcile()`;
  - posts an alert notification explaining the cause (a separate channel from the ongoing one).
- **Health gaps:** when an outage starts, open a `Gap` with the matching cause (`PERMISSION_LOST`, `LOCATION_OFF`, `SERVICE_KILLED` or `NO_FIX`) and `zoneId`, starting at the last heartbeat. Keep its `end` up to date (upsert the same id) until recovery.
- **`DefaultTrackingStateRepository`** replaces `IdleTrackingStateRepository`. It combines the status store, the effective schedule, active overrides and permission/location checks through a pure `TrackingStateReducer`, and exposes the state together with the cause and the paused-until time. It updates on app foreground too, not only from the worker.

### T9 — Minimal permission flow (implementer, M)
- **Screen:** `PermissionsRoute` is shown on first launch when fine location is missing, and reachable from the ATTENTION sheet.
- **Steps, in order:**
  1. Fine + coarse location.
  2. Background location: a prominent disclosure screen first, stating what is collected, why, and that it stays on the device. Then the system settings flow.
  3. Activity recognition.
  4. Notifications.
  5. Exact alarms (settings intent).
  6. Battery optimisation exemption (intent).
- **Skipping:** every step except fine location can be skipped. A skipped step degrades visibly, either as an ATTENTION cause or as a row on the permission screen.
- **Status:** each step shows granted/denied, and the screen re-checks status on resume.

### T10 — Debug sessions screen and raw export (implementer, M)
- **Screen:** Settings gets a "Sessions" entry (`SessionsRoute`). It lists sessions newest first, with start/end, trigger, endReason and ping count. The debug "test window" button lives here too.
- **Export:** a session or a date range can be exported through SAF `CreateDocument`, user-initiated only (rule 2).
  - GPX 1.1: a `trkpt` per ping with `time` and the accuracy in an extension.
  - CSV: columns `id,timestamp_utc,zone_id,lat,lng,accuracy_m,speed_mps,bearing,activity,source,session_id`.
- **Code:** pure formatters with unit tests. Phase 2b reads this CSV format as fixtures.

### T11 — Tests (test-writer, M)
- **JVM:**
  - `PingFilter`, covering each rule and its edges.
  - `CaptureModeMachine` transitions, including the no-activity-recognition fallback.
  - `EffectiveScheduleCalculator`: window edges, week boundary, an override week, manual START/PAUSE and each end mode, and DST forward/back in `Australia/Sydney` (first Sunday in October and first Sunday in April).
  - `TrackingStateReducer`, all four states and every cause.
  - The GPX/CSV formatters.
  - `TrackingController` with fakes: start, pause, resume, stop, crash recovery and Gap writing.
- **Robolectric:**
  - Alarm scheduling (`ShadowAlarmManager`): exact vs inexact, and re-arm from each receiver.
  - The permission screen's state rendering.
  - The toggle sheet's content per state (Compose).
- `HealthCheckWorker` with `TestListenableWorkerBuilder` and fakes.

### T12 — Device check and dogfood (deployer + owner, S)
1. Install the app on the Pixel.
2. Run the device checklist in the acceptance criteria.
3. The owner carries the phone for at least one real working day and exports the raw CSV for 2b.

## Acceptance criteria
- `gradlew.bat assembleDebug testDebugUnitTest :segmentation:test lintDebug` passes locally and in CI.
- ADR 0003 records the T1 outcome.
- **On the Pixel 10:**
  - With the debug test window, the app swiped away and the screen off, the session starts within 1 min of the window start (exact alarm granted). Pings accrue, and the session closes with `SCHEDULE_END` at the window end.
  - It behaves the same under `adb shell dumpsys deviceidle force-idle`.
  - **Reboot mid-window:** tracking resumes, and the outage appears as a Gap.
  - **Location permission revoked mid-window:** ATTENTION (NO_FINE_LOCATION or NO_BACKGROUND_LOCATION) within 15 min, an alert notification, and a `PERMISSION_LOST` Gap.
  - **Location toggled off:** ATTENTION (LOCATION_OFF) and a Gap.
  - **Time-zone change:** the alarm is re-armed (`adb shell dumpsys alarm | grep me.tarry`).
  - Notification Pause 1 h → PAUSED, then it auto-resumes. Stop → IDLE until the next window.
  - Toggle states render with the correct content descriptions.
  - Sessions screen exports GPX and CSV that open in a GPX viewer and a spreadsheet.
- No network calls (check that no code adds any network client), and `allowBackup` is still false.
- No schema change, so `1.json` is unchanged. If one was required, there is a v2 migration with a test.

## Risks and open questions
- **Background FGS start:** T1 decides. Fallback: the tap-to-start notification, which needs the owner's sign-off.
- **Activity Recognition:** if it's denied or unreliable, the distance-gating fallback costs battery. Tune `TrackingConfig` from dogfood data.
- **Schedule source:** decided. The engine reads the stored template, seeded with the preset, instead of a literal in code, so phase 6 only adds UI.
- **Only one device (Pixel):** OEM-killer behaviour (Samsung) goes untested until dogfooding expands.
