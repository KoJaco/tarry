package me.tarry.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import me.tarry.app.tracking.EffectiveScheduleCalculator
import me.tarry.app.tracking.ScheduleInputsProvider
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.app.tracking.TrackingConfig
import me.tarry.core.data.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When to arm the reminder: at [minuteOfDay] (local wall clock in [zone]) on the first day [isScheduledDay] accepts,
 * looking at today and the following [lookAheadDays] days; null when none is scheduled. Pure: a wall-clock time that
 * does not exist (a DST gap) moves forward by the gap, one that happens twice takes the first.
 *
 * Today still counts while it has not fired ([lastFiredDate] is not today) and [now] is before the reminder time plus
 * [graceMs]: before the time that is the time, after it (a late delivery, a re-arm at 5:33 pm) it is [now] itself, so a
 * re-arm cannot cancel today's reminder and it still arrives promptly. Past the grace without a fire (phone off) today is
 * skipped. Later days only count if their time is after [now].
 */
fun nextReviewReminderAt(
    now: Instant,
    zone: ZoneId,
    minuteOfDay: Int,
    lookAheadDays: Int,
    graceMs: Long,
    lastFiredDate: LocalDate?,
    isScheduledDay: (LocalDate) -> Boolean,
): Instant? {
    val today = now.atZone(zone).toLocalDate()
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    for (offset in 0..lookAheadDays) {
        val date = today.plusDays(offset.toLong())
        if (!isScheduledDay(date)) continue
        val at = date.atTime(time).atZone(zone).toInstant()
        if (offset == 0) {
            if (date != lastFiredDate && now.toEpochMilli() < at.toEpochMilli() + graceMs) return maxOf(at, now)
        } else if (at > now) {
            return at
        }
    }
    return null
}

/** Arms (or cancels) the single review-reminder alarm from the setting and the schedule. */
interface ReviewReminderScheduler {
    suspend fun rearm()
}

/** Which dates have any scheduled work window. */
interface ScheduledDays {
    /** Dates among [first] .. [first] + [lookAheadDays] with a window in the template or week overrides (manual overrides do not count). */
    suspend fun among(first: LocalDate, lookAheadDays: Int, zone: ZoneId): Set<LocalDate>
}

/** [ScheduledDays] from the same schedule inputs the tracking alarm uses. */
class InputsScheduledDays @Inject constructor(
    private val inputsProvider: ScheduleInputsProvider,
    private val calculator: EffectiveScheduleCalculator,
) : ScheduledDays {
    override suspend fun among(first: LocalDate, lookAheadDays: Int, zone: ZoneId): Set<LocalDate> {
        val start = first.atStartOfDay(zone).toInstant()
        val end = first.plusDays(lookAheadDays + 1L).atStartOfDay(zone).toInstant()
        val inputs = inputsProvider.inputs(start, end, zone).copy(manualOverrides = emptyList(), testWindowStartMs = null)
        return (0..lookAheadDays).map { first.plusDays(it.toLong()) }.filter { date ->
            calculator.activeIntervals(
                inputs, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant(), zone,
            ).isNotEmpty()
        }.toSet()
    }
}

/**
 * One inexact alarm ([AlarmManager.setWindow], no exact-alarm permission) at the reminder time on the next scheduled day.
 * When no day in the look-ahead is scheduled, a "recheck" alarm just past the look-ahead recomputes (and never posts), so
 * the reminder cannot stay disarmed for good. No timers: the OS delivers the alarms; [rearm] runs on every event that
 * can move them (setting, schedule, restore, boot, clock, a scheduled window starting).
 */
@Singleton
class AndroidReviewReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val scheduledDays: ScheduledDays,
    private val statusStore: TrackingStatusStore,
    private val clock: TrackingClock,
    private val config: TrackingConfig,
) : ReviewReminderScheduler {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override suspend fun rearm() {
        val reminder = pendingIntent(context, recheck = false)
        val recheck = pendingIntent(context, recheck = true)
        alarmManager.cancel(reminder)
        alarmManager.cancel(recheck)
        val current = settings.settings.first()
        if (!current.reviewReminderEnabled) return
        val now = Instant.ofEpochMilli(clock.nowMs())
        val zone = clock.zone()
        val lookAhead = config.scheduleLookAheadDays.toInt()
        val firstDay = now.atZone(zone).toLocalDate()
        val days = scheduledDays.among(firstDay, lookAhead, zone)
        val lastFired = statusStore.current().lastReviewReminderDate
        val at = nextReviewReminderAt(
            now, zone, current.reviewReminderMinuteOfDay, lookAhead, config.reviewReminderGraceMs, lastFired,
        ) { it in days }
        if (at != null) {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), config.reviewReminderWindowMs, reminder)
        } else {
            val beyond = firstDay.plusDays(lookAhead + 1L).atStartOfDay(zone).toInstant()
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, beyond.toEpochMilli(), config.reviewReminderWindowMs, recheck)
        }
    }

    internal companion object {
        private const val REQUEST_CODE = 7
        private const val RECHECK_REQUEST_CODE = 9
        const val ACTION_RECHECK = "me.tarry.app.action.REVIEW_REMINDER_RECHECK"

        /** The reminder itself, or with [recheck] the recompute-only alarm (a distinct action, so both can be armed). */
        fun pendingIntent(context: Context, recheck: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context,
            if (recheck) RECHECK_REQUEST_CODE else REQUEST_CODE,
            Intent(context, ReviewReminderReceiver::class.java).also { if (recheck) it.setAction(ACTION_RECHECK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
