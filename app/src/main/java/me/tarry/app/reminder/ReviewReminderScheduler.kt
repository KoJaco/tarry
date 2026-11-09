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
import me.tarry.app.tracking.TrackingConfig
import me.tarry.core.data.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The next instant, strictly after [now], at [minuteOfDay] (local wall clock in [zone]) on a day [isScheduledDay] accepts.
 * Looks at today and the following [lookAheadDays] days; null when none of them is scheduled. Pure: a wall-clock time
 * that does not exist (a DST gap) moves forward by the gap, one that happens twice takes the first.
 */
fun nextReviewReminderAt(
    now: Instant,
    zone: ZoneId,
    minuteOfDay: Int,
    lookAheadDays: Int,
    isScheduledDay: (LocalDate) -> Boolean,
): Instant? {
    val today = now.atZone(zone).toLocalDate()
    val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    for (offset in 0..lookAheadDays) {
        val date = today.plusDays(offset.toLong())
        if (!isScheduledDay(date)) continue
        val at = date.atTime(time).atZone(zone).toInstant()
        if (at > now) return at
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
 * No timers: the OS delivers the alarm; [rearm] runs on every event that can move it (setting, schedule, boot, clock).
 */
@Singleton
class AndroidReviewReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val scheduledDays: ScheduledDays,
    private val clock: TrackingClock,
    private val config: TrackingConfig,
) : ReviewReminderScheduler {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override suspend fun rearm() {
        val pending = pendingIntent(context)
        alarmManager.cancel(pending)
        val current = settings.settings.first()
        if (!current.reviewReminderEnabled) return
        val now = Instant.ofEpochMilli(clock.nowMs())
        val zone = clock.zone()
        val lookAhead = config.scheduleLookAheadDays.toInt()
        val days = scheduledDays.among(now.atZone(zone).toLocalDate(), lookAhead, zone)
        val at = nextReviewReminderAt(now, zone, current.reviewReminderMinuteOfDay, lookAhead) { it in days } ?: return
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), config.reviewReminderWindowMs, pending)
    }

    internal companion object {
        private const val REQUEST_CODE = 7

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReviewReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
