package me.tarry.app.tracking

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject

/** Arms exactly one alarm: the next schedule transition. */
interface ScheduleAlarmScheduler {
    /** True if the OS lets us schedule exact alarms. Always true below API 31. */
    fun canScheduleExact(): Boolean

    /**
     * Replaces the armed alarm with one at [at] (or cancels it if null).
     * Returns false when only an inexact alarm could be armed (degraded, ADR 0003).
     */
    fun arm(at: Instant?): Boolean
}

class AndroidScheduleAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ScheduleAlarmScheduler {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun arm(at: Instant?): Boolean {
        val pending = TrackingIntents.scheduleAlarm(context)
        if (at == null) {
            alarmManager.cancel(pending)
            return canScheduleExact()
        }
        val exact = canScheduleExact()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
        }
        return exact
    }
}
