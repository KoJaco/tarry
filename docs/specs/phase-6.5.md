# Phase 6.5: Trust pass

Handoff: `docs/handoffs/phase-6.5-trust-pass.md` (on the `docs/handoffs-6.5-to-8` branch until PR #26 merges).

Goal: before the numbers are used for invoicing, find out where tracking can stop without anyone noticing, fix what fails, and measure the battery cost once.

## 1. The 24 Oct silent drive (postmortem)
- **Cause:** sitting in the parked car (IN_VEHICLE, ~0.3 m/s) for 5 min tripped the no-progress rule, which demoted capture to STATIONARY with the 60 s request. That request got no fixes for 12 min of driving (11:16:59–11:28:51, 2.7 km). Health waits 20 min and segmentation bridges up to 45 min, so no Gap was written.
- **Fix (073ef8d, narrowed in 5b75d60 and the cap commit after review):**
  - A: segmentation writes a NO_FIX Gap for more than 5 min with no fix of any accuracy, when the good fixes either side are further apart than a trip (`SegmentationParams.unrecordedMoveMinMs`, `SEGMENTATION_VERSION` 9). Poor fixes still record a trip, so a tunnel or network-only drive stays a trip (owner decision, 3 Nov).
  - B: a no-progress demotion keeps the 10 s request while Activity Recognition still says moving (`TrackingConfig.noProgressKeepsMovingRequest`). If AR never reports STILL, the first fix 20 min after the demotion switches to the 60 s request (`noProgressKeptRequestMaxMs`; owner decision, 3 Nov).
- **Detect sooner:** golden tests `aDriveWithNoFixesAfterSittingInTheCarIsAGap` and `aDriveWithOnlyPoorFixesIsATripNotAGap` keep both shapes covered. A local replay of the owner's 24–30 Oct data (kept out of the repo) flagged only this hole.

## 2. Lifecycle results (Pixel 10, Android 17, 1–2 Nov 2026)
| Case | Result | Notes |
|---|---|---|
| App update mid-session | PASS | Session ended KILLED, 4 s SERVICE_KILLED Gap, capture restarted by the update receiver. |
| Location toggled off | PASS | ATTENTION shown as soon as the app is opened; first fix 4 s after re-enable. No Gap, by design: the silence was under the 20 min health threshold. The alert notification comes from the ≤15 min health worker. |
| Background location revoked | PASS | Bounded SERVICE_KILLED and PERMISSION_LOST Gaps; capture resumed on re-grant from the in-app prompt. |
| Force-stop | PASS | Gap from the stop to the next launch, which reconciled and resumed. Android blocks alarms until the app is next launched. |
| Doze | PASS | Forced deep idle for 6 min: fixes every ~62 s, no starvation. The 18:30 window-end alarm fired at 18:30:01 with the device in deep idle since 18:27. |
| Reboot mid-session | PASS | Untouched phone: BOOT_COMPLETED reached Tarry ~46 s after boot, and capture resumed 20 s later (5 s deferred-start alarm, delivered late). The old session closed KILLED at its last fix; the off period is a SERVICE_KILLED Gap. |
| Time-zone change | **FIXED** (d889f04) | Pings take the new zone at once and the session continues without a Gap. **Bug:** a Stop inside a window stores its end as the next window start in the zone at that moment, and nothing recomputed it after a zone change. Stopping on Perth time, then returning to Sydney, suppressed 07:30–10:30 the next morning. TIME_SET and TIMEZONE_CHANGED now re-bound the Stop in the new zone before reconciling. Tests: `TrackingControllerTest.aZoneChangeRebindsAStopToTheNextWindowInTheNewZone`, `aClockChangeWithoutAStopOnlyRearmsInTheNewZone`. Re-tested on the Pixel on 3 Nov: a stop on Perth time re-bound to 07:30 Sydney after the switch back, and the alarm followed. |
| DST change | PASS (JVM) | No device DST until April 2027. Covered by `EffectiveScheduleCalculatorTest` DST cases, `DayLayoutTest.dstShortDaySydney` / `dstFallBackDaySydney` and `TimelineViewModelTest.dstDayIs23Hours`. |

## 3. Battery
Tracked work day, Mon 2 Nov: batterystats reset 07:16, unplugged until ~20:27 (13 h 10 m on battery, schedule window 07:30–18:30). A heavy-use day: screen on 3 h 22 m.

| | Total | Per hour on battery |
|---|---|---|
| Device drain | 3,324 mAh (68% of 4,897 mAh) | ~252 mAh/h |
| Tarry (batterystats estimate) | 156 mAh (3.2% of capacity) | ~14 mAh/h across the 11 h window |
| of which GNSS | 151 mAh (3 h 26 m of GPS on) | |

- Almost all of Tarry's cost is GPS; CPU, radio and wakelocks are under 3 mAh together.
- **Baseline (untracked day):** pending. Measure on the next day off and add it here. Owner decision: GPS stays always on in sessions, so this is a measurement, not tuning.

## 4. Small items (e650b1f)
- `DayCanvas`: the always-true summary check removed.
- `JobColumnTest`: the drag-preview test now actually checks the preview (it failed with the old modifier order).
- New test: the job column ignores input in multi-select.

## Known gaps
- **Samsung and other OEMs:** only a Pixel was tested. OEM battery managers can kill the service or delay alarms more aggressively; untested.
- **Location-off alert latency:** with the app closed, the alert can take up to the 15 min health worker period.
