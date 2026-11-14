# Post-launch backlog

Small items deliberately left out of v1.0.0. Each needs a handoff (and a test, rule 7) when picked up.

## Timeline empty state hides a permission problem
**Seen:** 14 Nov 2026 on the emulator. With location set to "Allow only while using the app", the pill correctly shows
**Attention** and the tracking sheet says "Location access is not set to 'Allow all the time'" with **Fix permissions**.
But today's empty timeline still reads "Not tracking: outside your schedule" (`timeline_empty_reason_outside_schedule`).
That is true, but it hides the problem that matters: the next scheduled window won't start.

**Fix:** when a standing attention issue exists (missing fine or background location, exact alarms denied), the empty
state's reason line shows that issue instead, using the same wording as the sheet, and perhaps the same Fix action.
Test: an empty day with `NO_BACKGROUND_LOCATION` shows the permission line, not "outside your schedule".

Also check whether a standing permission issue outside a window posts a notification, or only changes the pill. Inside a
window it alerts and records a Gap (verified in code).

## Battery phase follow-ups
- An in-app line showing how long precise location was used today, and a log of each day's battery mode
  (`docs/handoffs/phase-battery-build.md`).

## Trial lock
- Health Gap rows already stored can still show on the Timeline after the lock starts (display only; new windows
  aren't recorded).

## Marketing
- Paywall and battery-mode screenshots for the store listing.
