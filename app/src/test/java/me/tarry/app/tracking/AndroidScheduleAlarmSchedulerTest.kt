package me.tarry.app.tracking

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidScheduleAlarmSchedulerTest {
    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: AndroidScheduleAlarmScheduler

    private val first = Instant.ofEpochMilli(2_000_000_000_000L)
    private val second = Instant.ofEpochMilli(2_000_003_600_000L)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        scheduler = AndroidScheduleAlarmScheduler(context)
    }

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms

    @Test
    fun exactAllowedArmsOneExactWakeupAlarmAtTheInstant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        assertThat(scheduler.canScheduleExact()).isTrue()
        assertThat(scheduler.arm(first)).isTrue()

        val alarm = alarms().single()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(first.toEpochMilli())
        assertThat(alarm.allowWhileIdle).isTrue()
        // An exact alarm has no delivery window (inexact ones get a window from the shadow).
        assertThat(alarm.windowLengthMs).isEqualTo(0L)
    }

    @Test
    fun exactDeniedFallsBackToOneInexactAlarmAndReportsDegraded() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertThat(scheduler.canScheduleExact()).isFalse()
        assertThat(scheduler.arm(first)).isFalse()

        val alarm = alarms().single()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(first.toEpochMilli())
        assertThat(alarm.allowWhileIdle).isTrue()
        assertThat(alarm.windowLengthMs).isNotEqualTo(0L)
    }

    @Test
    fun rearmingReplacesTheAlarmInsteadOfAddingOne() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        scheduler.arm(first)
        scheduler.arm(second)

        assertThat(alarms().map { it.triggerAtMs }).containsExactly(second.toEpochMilli())
    }

    @Test
    fun rearmingAcrossAPermissionChangeStillLeavesExactlyOneAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.arm(first)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.arm(second)

        assertThat(alarms().map { it.triggerAtMs }).containsExactly(second.toEpochMilli())
    }

    @Test
    fun armingNullCancelsTheAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.arm(first)

        scheduler.arm(null)

        assertThat(alarms()).isEmpty()
    }
}
