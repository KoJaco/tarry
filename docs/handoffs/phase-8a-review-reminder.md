# Handoff: phase 8a.1 — review reminder (moved up from 8a.2, owner 9 Nov)

**From:** Claude (planner, `phase-8`) → **To:** implementer · **Date:** 2026-11-09 · **Branch:** `feat/phase-8-launch` (checked out; never main).

Read first: `AGENTS.md` (rules 5, 6), `docs/design/ui-rules.md`, `docs/decisions/0003-background-start.md`, `docs/handoffs/phase-8-launch.md` § "Owner decisions, 2026-11-09" (reminder: scheduled days only, only when that day still has something needing review, inexact alarm).

**Today:** Settings → Reminders has two display-only rows ("Review reminder" off, reminder time). `SettingsRepository` already has `review_reminder_enabled`, `review_reminder_minute_of_day` (default 17:30) and setters `setReviewReminderEnabled`, `setReviewReminderMinuteOfDay`. No channel, alarm or receiver exists.

## Build
1. **Settings rows:**
   - "Review reminder" becomes a `Switch` row (state description on/off).
   - "Reminder time" opens the app's existing time picker dialog (find the one Schedule or the drawer uses), respects the system 12/24-hour setting, and is disabled (muted, still shows value) while the reminder is off.
   - Turning the reminder on with Android 13+ and notifications not granted: request `POST_NOTIFICATIONS` (reuse the Permissions screen's launcher pattern). If refused, keep the setting on but show the row subtitle "Notifications are off for Tarry. Turn them on to get reminders." with a tap that opens the app's notification settings (rule 6: visible, not silent).
   - Row subtitle when on: "Scheduled days at 5:30 pm, if something needs review".
2. **`ReviewReminderScheduler`** (in `app/.../tracking/` or a new `app/.../reminder/` package):
   - `rearm()`: cancel, then if enabled, find the next instant at the reminder minute on a day the schedule has any work window (use the existing schedule engine/templates + overrides; same source `ScheduleAlarmScheduler` uses), in the device's current time zone, strictly after now. Set an **inexact** alarm with `AlarmManager.setWindow(RTC_WAKEUP, at, windowMs, pendingIntent)`; `windowMs` = new `TrackingConfig.reviewReminderWindowMs` (10 min). No exact-alarm permission needed.
   - Pure function for the next-fire calculation (injected clock + zone) so it's JVM-testable.
   - Call `rearm()` when: the reminder setting or time changes; the schedule changes (wherever schedule edits already trigger `ScheduleAlarmScheduler` rescheduling); boot / package replaced / time or time-zone change (reuse the existing receiver(s) that re-arm schedule alarms; add the reminder call there); and after the alarm fires.
3. **On fire** (a `BroadcastReceiver`, `goAsync()` + the app's existing coroutine scope pattern):
   - Re-arm for the next day first.
   - Determine whether **today** (local date of the fire) has anything needing review, using **the same predicate as the Timeline's Needs review filter** (find it; reuse, don't copy logic). If nothing, post nothing.
   - Otherwise post on a new channel `review_reminder` (name "Review reminders", default importance, created where the other channels are created). Title "Review your day", text plural-aware: "1 item needs review" / "%d items need review". Small icon `ic_stat_tracking`. Tap opens MainActivity on today's Timeline with the Needs review filter on (add an intent extra handled where the app already handles notification taps, if any; otherwise in MainActivity → shell). Auto-cancel.
4. **Strings** in resources, sentence case, no exclamation marks.

## Tests
- JVM: next-fire calculation — today before the time on a scheduled day → today; after → next scheduled day; skips unscheduled days; DST forward/back in `Australia/Sydney`; time-zone change; no scheduled days → no alarm.
- Receiver/notifier: nothing to review → no notification; items → notification with the right count; re-arm always happens (fake AlarmManager seam / Robolectric `ShadowAlarmManager`).
- Settings Compose: the switch toggles the setter; the time row disabled when off; time picker sets minute-of-day; denied-notifications subtitle shows. Font 2.0.

## Also (small, separate commit)
`dayName(…, TextStyle.SHORT)` in `ui/schedule/ScheduleDaySheet.kt` ("copy to" chips) and `ui/schedule/ScheduleLabels.kt` (schedule summary) can show the en-AU "M." / "Tu." quirk. Switch them to the shared `weekdayLabel` helper (`ui/timeline/WeekStrip.kt`) with a test in `Locale("en","AU")`.

## Rules
No own timers or polling (rule 5). Small diffs. `gradlew.bat testDebugUnitTest lintDebug assembleDebug` green. Commits by part, explicit paths (include this handoff in the first), never `.idea/`, each ending with exactly `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No phone installs, no `connectedDebugAndroidTest`, no Room schema change. Report files, tests, decisions, skips.
