# 0003 — Starting location capture from the background

Status: accepted (2026-10-21). Outcome of phase 2a task T1 (background start spike).

## Context
Phase 2a starts a `location`-type foreground service at the start of each scheduled window, usually while the app is not running and the phone is in a pocket. Android 12+ blocks foreground-service starts from the background except under listed exemptions, and Android 14+ also requires the app to be eligible for location access when a `location` FGS starts. We needed to know, on our only device and target SDK, whether an alarm can do this, and what fails when a precondition is missing.

## Spike
Throwaway commit `0de3158` (reverted in `977a0b5`). An adb-triggered receiver armed an alarm. The alarm receiver called `startForegroundService`, and the service called `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` and then requested fused updates at high accuracy.

- Device: Pixel 10, Android 17 (`CP2A.260805.005`), Play services 26.40.32. App targetSdk 37.
- Setup: the app process was killed before each alarm fired (checked with `pidof`). Fine, coarse, background location and notifications were granted with `pm grant`. Exact alarms were toggled with `appops set me.tarry.app SCHEDULE_EXACT_ALARM allow|default`, which is the same state the `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` settings screen writes.

| # | Alarm | Device state at fire | Background location | Result |
|---|---|---|---|---|
| A | `setExactAndAllowWhileIdle`, exact allowed | Deep IDLE (`force-idle`), screen off, unplugged, process dead | granted | FGS start **allowed** (`code:ALARM_MANAGER_WHILE_IDLE`). `startForeground` OK. First fix after **2 s**, 21 m accuracy. |
| B | `setWindow` (60 s window), exact revoked | Doze disabled, process dead | granted | Alarm fired; `startForegroundService` threw **`ForegroundServiceStartNotAllowedException`** (`BFGS denied: true; code:DENIED`). |
| B′ | same as B | Unplugged, screen off, process dead | granted | Alarm **held indefinitely** (`policyWhenElapsed … battery_saver=+364d`) even though Battery Saver was off. It only fired after the battery was reset to plugged in. |
| C | `setExactAndAllowWhileIdle`, exact allowed | Screen off, process dead | **revoked** | FGS start allowed, but `startForeground` threw **`SecurityException`**: "…the app must be in the eligible state/exemptions to access the foreground only permission". No fixes. |

An earlier run of A with the screen accidentally awake gave the same result (fix in under 1 s).

## Decision
- **Scheduled starts use `setExactAndAllowWhileIdle`.** It works with the app dead, the screen off and the device in deep Doze, and a fix arrives within seconds. We don't need the tap-to-start fallback as the primary path.
- **Exact alarms and background location are both hard preconditions for scheduled capture.** Without exact alarms, the inexact alarm can't start the FGS at all (and may not even fire while unplugged). Without background location, the alarm exemption alone doesn't make the app eligible for a `location` FGS.
- **Degraded paths fail loudly (rule 6):**
  - When exact alarms are denied, `ScheduleAlarmScheduler` still arms an inexact alarm (T6), so the app wakes when it can. On that wake, `TrackingController` must not assume the start worked. `startForegroundService` throws `ForegroundServiceStartNotAllowedException`; catch it, set ATTENTION `EXACT_ALARM_DENIED`, open a Gap, and post the health alert notification. Tapping the alert opens the app, where the user can start tracking in the foreground. This reuses the T8 alert, so it isn't a new fallback mechanism.
  - When background location is missing, `startForeground` throws `SecurityException`. The service must catch it, stop itself, and record ATTENTION `NO_BACKGROUND_LOCATION` plus a `PERMISSION_LOST` Gap.
  - The `HealthCheckWorker` and the reducer treat both as broken preconditions, so they also surface before a window starts.
- The permission flow (T9) must present background location and exact alarms as needed for scheduled tracking, and not as optional extras.

## Consequences
- Reliability depends on the user keeping two special permissions. Both can be revoked from system settings at any time, so the reducer checks them on every app foreground, and the `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` receiver re-arms the alarm.
- Manual starts from the UI or the notification happen while the app is in the foreground or handling a notification action. They don't rely on these exemptions.
- Tested on one Pixel only. OEM task killers (for example Samsung) can still prevent the alarm from waking a dead app. That risk stays open until dogfooding expands.
- If a future Android release removes the exact-alarm FGS exemption, the fallback is the high-priority "Tap to start tracking" notification, and that switch needs the owner's sign-off.
