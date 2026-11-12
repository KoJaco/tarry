# Handoff: battery phase — tracking modes and the low-battery switch

**From:** Claude (planner) → **To:** implementer · **Date:** 2026-11-13 · **Branch:** `feat/phase-battery` (from main; never main).
Read first: `AGENTS.md` (rules 5, 6, 7, 11), `docs/handoffs/phase-battery.md` (evidence + owner decisions), memory note:
October's whole-app switch to BALANCED/LOW_POWER broke segmentation, so **moving capture stays HIGH_ACCURACY in Precise and
Balanced**.

## Already done (drop from the plan doc)
- Window-end grace already has "Off" (`Settings.ALLOWED_WINDOW_END_GRACE_MIN` contains 0).
- The ongoing notification already re-posts only on a mode change (`TrackingService.kt` `distinctUntilChanged`).
- Deferred (post-launch, not in this PR): the in-app "precise location used for N h today" line and the per-day mode log.

## Build exactly this
### 1. Profiles in `TrackingConfig` (rule 5: no literals elsewhere)
```kotlin
enum class PowerMode { PRECISE, BALANCED, SAVER }   // core-data settings model (it is a user setting)
data class CaptureProfile(
    val stationaryPriority: LocationPriority, val stationaryIntervalMs: Long,
    val movingPriority: LocationPriority, val movingIntervalMs: Long, val movingMaxUpdateDelayMs: Long,
    val noProgressKeptRequestMaxMs: Long,
)
```
`TrackingConfig.profiles: Map<PowerMode, CaptureProfile>` replacing the single stationary/moving request fields
(`stationaryPriority`, `stationaryFixIntervalMs`, `movingPriority`, `movingIntervalMs`, `movingMaxUpdateDelayMs`,
`noProgressKeptRequestMaxMs` move into the profiles; keep the doc comments with the profile):

| | stationary | moving | batch (max delay) | kept moving request max |
|---|---|---|---|---|
| PRECISE | HIGH_ACCURACY 60 s | HIGH_ACCURACY 10 s | 0 | 20 min |
| BALANCED (default) | HIGH_ACCURACY **180 s** | HIGH_ACCURACY 10 s | **30 s** | **5 min** |
| SAVER | **BALANCED 300 s** | HIGH_ACCURACY **30 s** | **120 s** | **3 min** |

`minUpdateIntervalMs` = the interval (as today). `waitForAccurateLocation`, `maxUpdateAgeMs`, entry/exit rules unchanged.
Precise must produce **exactly** today's requests (existing `CaptureModeMachineTest` expectations stay green unchanged when
run with PRECISE).

### 2. `CaptureModeMachine`
- Constructor takes the starting `CaptureProfile` (alongside config). `requestStationary()` / `requestMoving()` read it.
- `fun setProfile(p: CaptureProfile): MachineStep` — stores it; if the profile changed, returns a `RequestLocation` for the
  **current** mode's request (MOVING ⇒ moving request; STATIONARY with the moving request kept ⇒ moving request;
  STATIONARY ⇒ stationary request). No mode change, no geofence change. Same profile ⇒ empty step.

### 3. Settings (core-data)
- `Settings.powerMode: PowerMode = BALANCED` (key `tracking_power_mode`, stored as name, unknown ⇒ BALANCED) and
  `Settings.lowBatterySaver: Boolean = true` (key `low_battery_saver`). Setters in `SettingsRepository`. Both are ordinary
  user settings (included in backup like the stay radius); add to backup/restore round-trip tests if those enumerate keys.
  Upgrades get BALANCED (no stored value).

### 4. Battery source and the effective mode (app)
- `interface BatterySource { fun read(): BatteryReading?; val changes: Flow<BatteryReading> }`,
  `data class BatteryReading(val percent: Int, val charging: Boolean)`. `AndroidBatterySource`: `read()` = sticky
  `ACTION_BATTERY_CHANGED` (`registerReceiver(null, filter)`); `changes` = a `callbackFlow` registering a receiver for
  `ACTION_BATTERY_CHANGED` (dynamic only; nothing in the manifest), `distinctUntilChanged()`.
- Pure `LowBatteryRule` (in `TrackingConfig`: `lowBatteryEnterPercent = 20`, `lowBatteryExitPercent = 25`):
  `next(active: Boolean, r: BatteryReading?): Boolean` — charging ⇒ false; null ⇒ keep `active`; `!active && percent <= 20`
  ⇒ true; `active && percent >= 25` ⇒ false; else keep.
- Effective mode = `if (lowBatterySaver && lowBatteryActive) SAVER else powerMode`.
- `TrackingStatusStore` gets `lowBatterySaverActive: Boolean` (persisted, cleared at session end) so UI and health can show it.
- `CaptureCoordinator`:
  - `begin`: read the settings + one battery reading, compute active + effective mode, build the machine with that profile,
    persist `lowBatterySaverActive`.
  - While a session is open, collect `settings` (powerMode, lowBatterySaver) combined with `battery.changes` on the
    capture scope (start in `begin`, cancel in `end`); on a change of the **effective mode** call `machine.setProfile` under
    the mutex and `apply` the step; persist `lowBatterySaverActive` on change. This is an OS broadcast/settings change, not
    our own timer (rule 5).
  - Fakes for tests: `FakeBatterySource` with a `MutableSharedFlow`.

### 5. UI
- Settings → Tracking group, after "Stay radius": row **"Battery use"** (subtitle = mode label; when the low-battery switch is
  active right now: "Saver · battery low") opening a `PickerSheet` (same pattern as GRACE: `details` per option) and below
  it a switch row **"Switch to Saver at 20% battery"** (existing switch row component, ≥ 48 dp).
  - Picker explanation: "How often Tarry checks your location. More often means more detail and more battery."
  - Precise — "Every 10 seconds while you move and every minute at a site. Most detail, most battery."
  - Balanced (recommended) — "Every 10 seconds while you move, every few minutes at a site. Recommended."
  - Saver — "Fewer, rougher fixes. Trips look approximate and stops under about 10 minutes may be missed."
  - Switch subtitle: "Uses Saver until you charge or the battery is back above 25%."
  - Labels: "Precise", "Balanced", "Saver".
- **Tracking health** screen: a row/line "Battery use: Balanced" (or "Saver, battery low" while the switch is active).
- **Tracking sheet** (`ui/shell/TrackingSheet.kt`), while tracking and the switch is active: one muted line "Battery low:
  using Saver". The pill does not change (tracking is still running; rule 6 is about not tracking).
- Strings in a new `res/values/strings_battery.xml`; Australian English, sentence case.

### 6. Tests (rule 7)
- `CaptureModeMachineTest`: each profile's stationary and moving requests (priority, interval, max delay); kept-request
  cap per profile; `setProfile` in MOVING / STATIONARY / STATIONARY-with-kept-request; same profile ⇒ no effect.
  PRECISE reproduces today's requests.
- `LowBatteryRuleTest`: 21→20 enters, 24 stays, 25 exits, charging exits and blocks entry, null keeps.
- Coordinator test (existing coordinator test style with fakes): begin at 15% with the switch on ⇒ SAVER request; battery
  to 30% ⇒ BALANCED request issued; switch off ⇒ back to chosen mode; powerMode change mid-session re-requests; status
  `lowBatterySaverActive` follows.
- Settings repository: defaults (BALANCED, true), unknown stored mode ⇒ BALANCED, round-trip.
- **Segmentation safety (golden):** in `:segmentation` tests, a synthetic day (stays of 15 min, 40 min, 2 h joined by
  trips) sampled like Precise (10 s moving / 60 s stationary) vs like Balanced (10 s / 180 s) vs Saver (30 s / 300 s with
  ±40 m noise at stays): the same visits (count, places within the match radius, start/end within 3 min for Balanced,
  5 min for Saver) and trips. If Saver misses something, record it as the documented trade-off rather than tuning
  segmentation.
- Compose: Settings battery row + picker details + switch (48 dp, font 2.0, light/dark); health line; sheet line.

## Verify
`gradlew.bat testDebugUnitTest :segmentation:test lintDebug assembleDebug` green. No device installs, no
`connectedDebugAndroidTest`.

## Known risk (note in the PR, don't fix here)
A killed session closes at its last heartbeat; with a 180 s stationary interval the last heartbeat can be up to 3 min old,
so a kill + quick restart is a little likelier to exceed the 5-min short-interruption limit at a site.
