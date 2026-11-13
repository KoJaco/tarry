# Plan: battery phase (after 8b, before the 8c release)

**From:** Claude (planner) · **Date:** 2026-11-12 · **Status:** decided, not started. Starts after 8b (8b.1 + 8b.2) merges;
ships before 8c so launch users get it. Plan in detail (plan mode) before building.

## Why
On 12 Nov the owner's Pixel dropped 19% over the day. Android's own numbers (`dumpsys batterystats`, read only):
- **GPS on for 6 h 17 m, blamed on Tarry**, out of a 9 h 56 m session; device-wide GPS drain ≈ 271 mAh (4,932 mAh battery).
- Tarry's own cost ≈ 206 mAh (~4%); its CPU was only ~4 min all day. Workers (segmentation, geocode, health) are seconds
  each. So Tarry's real share was **~8–10%** (app + most of the GPS drain); screen time and other apps made up the rest.
- Smaller items: ~3,500 fused-location wakeups (`GCoreFlp`), 1,315 network-location scans, 206 activity-detection
  alarms, the ongoing notification re-posted 130 times.

From the phone's own pings (daily cadence, 3–12 Nov):

| Day | 10 s "moving" cadence | …of which effectively still (< 0.5 m/s) |
|---|---|---|
| typical (3–11 Nov) | 3.0–4.9 h | 1.2–2.3 h |
| 12 Nov | **5.9 h** | **2.85 h** |

Cause: both modes use `HIGH_ACCURACY` (`TrackingConfig`: moving every 10 s with no batching; stationary every 60 s, which
keeps the GPS chip effectively on), and Tarry is slow to leave moving mode when still (`noProgressKeepsMovingRequest`, up
to `noProgressKeptRequestMaxMs` = 20 min; stationary entry needs 3 min inside 50 m).
History: October's switch of the *whole* app to BALANCED/LOW_POWER gave 50–100 m network fixes and broke segmentation
(memory "accuracy first"). The new modes keep precise GPS while travelling and save it mainly while at a site.

## Owner decisions (2026-11-12)
1. **Three tracking modes, a user setting: Precise · Balanced · Saver.** **Balanced is the default** (new installs and
   upgrades). Each option shows plain info text about what it changes and costs.
2. **A separate toggle: "Switch to Saver at 20% battery"** (on/off), so people can keep tracking precisely at low
   battery if they want. The switch is visible (pill / Tracking health say so, rule 6) and reverts when the battery
   recovers or charging starts.
3. Its own phase, after 8b.

## Proposed mode definitions (to confirm in planning, with measurements)
- **Precise** — today's behaviour (HIGH_ACCURACY; 10 s moving; 60 s stationary).
- **Balanced (default)** —
  - leave moving mode **quickly when still**: an Activity Recognition STILL transition (already registered) enters
    stationary at once; cut the no-progress hold from 20 min to a few minutes;
  - **stationary without warm GPS**: rely on the stay geofence exit and activity transitions to notice departure, plus a
    precise check every 3–5 min instead of every 60 s;
  - **batch while moving** (`setMaxUpdateDelayMillis`, e.g. 60–120 s; still 10 s fixes, far fewer wakeups);
  - optional: cadence by activity (10 s in a vehicle, 20–30 s on foot).
- **Saver** — no GPS while at a site (geofence + activity + network fixes only); moving fixes every ~60 s.
  Info text: trips look approximate; stops under about 10 min may be missed or merged.
- All values live in `TrackingConfig` (one profile per mode); no scattered literals (rule 5).
- Also, in every mode: update the ongoing notification only when its text changes.

## Also in scope
- **Window-end grace**: add an "Off" option to "After the window ends" and say it uses battery.
- **Visible cost** in Tracking health, measured by Tarry itself (no extra permission): e.g. "Precise location used for
  3 h 10 min today" and the current mode.
- **Measurement first**: a small per-day log of time in each capture mode and fix counts (debug view + exportable), used
  to compare dogfood days against `batterystats` GPS time before and after.

## Acceptance (draft)
- Balanced: GPS-on time on a typical work day roughly halves versus Precise (target from 12 Nov's data: ~6 h → ~3 h),
  with no regression on the recorded golden traces (visits, trips, short stops ≥ 5 min) — segmentation tests stay green.
- Saver: documented trade-offs verified on a dogfood day.
- The 20% toggle: switches at ≤ 20% (not while charging), reverts above (with hysteresis, e.g. 25%) or on charging;
  always shown; never silent.
- Tests: mode profiles, the STILL → stationary path, geofence-driven exit in Balanced/Saver, the battery switch with an
  injected battery source, settings UI (info text, 48 dp, light/dark, font 2.0).
