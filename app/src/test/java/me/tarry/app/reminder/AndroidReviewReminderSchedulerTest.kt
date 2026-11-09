package me.tarry.app.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.app.tracking.createTrackingStatusDataStore
import me.tarry.core.data.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private class FakeScheduledDays(var days: (LocalDate) -> Boolean) : ScheduledDays {
    override suspend fun among(first: LocalDate, lookAheadDays: Int, zone: ZoneId): Set<LocalDate> =
        (0..lookAheadDays).map { first.plusDays(it.toLong()) }.filter(days).toSet()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidReviewReminderSchedulerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sydney = ZoneId.of("Australia/Sydney")
    private val config = TrackingConfig()
    private val weekdays = FakeScheduledDays { it.dayOfWeek.value <= DayOfWeek.FRIDAY.value }
    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var settings: SettingsRepository
    private lateinit var clock: FakeClock
    private lateinit var status: TrackingStatusStore
    private lateinit var scheduler: AndroidReviewReminderScheduler

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, sydney).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("s.preferences_pb") })
        clock = FakeClock(now = at(9, 9), zone = sydney) // Monday 09:00
        status = TrackingStatusStore(createTrackingStatusDataStore(scope) { folder.root.resolve("status.preferences_pb") })
        scheduler = AndroidReviewReminderScheduler(context, settings, weekdays, status, clock, config)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms

    @Test
    fun enabledArmsOneInexactWindowedWakeupAtTheNextScheduledReminderTime() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)

        scheduler.rearm()

        val alarm = alarms().single()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(at(9, 17, 30))
        assertThat(alarm.windowLengthMs).isEqualTo(config.reviewReminderWindowMs)
        assertThat(config.reviewReminderWindowMs).isEqualTo(10 * 60_000L)
    }

    @Test
    fun disabledArmsNothing() = runBlocking<Unit> {
        scheduler.rearm()

        assertThat(alarms()).isEmpty()
    }

    @Test
    fun turningItOffCancelsTheArmedAlarm() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        scheduler.rearm()
        settings.setReviewReminderEnabled(false)

        scheduler.rearm()

        assertThat(alarms()).isEmpty()
    }

    @Test
    fun changingTheTimeReplacesTheAlarm() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        scheduler.rearm()
        settings.setReviewReminderMinuteOfDay(19 * 60)

        scheduler.rearm()

        assertThat(alarms().map { it.triggerAtMs }).containsExactly(at(9, 19))
    }

    @Test
    fun noScheduledDaysLeavesNoAlarm() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        scheduler.rearm()
        weekdays.days = { false }

        scheduler.rearm()

        assertThat(alarms()).isEmpty()
    }

    @Test
    fun afterTheReminderTimeItMovesToTheNextScheduledDay() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        clock.now = at(13, 17, 41) // Friday past the 17:30 reminder and its 10 min window

        scheduler.rearm()

        assertThat(alarms().single().triggerAtMs).isEqualTo(at(16, 17, 30))
    }

    @Test
    fun aTimeZoneChangeMovesTheAlarmToTheNewLocalTime() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        scheduler.rearm()
        val perth = ZoneId.of("Australia/Perth")
        clock.zone = perth

        scheduler.rearm()

        // Same instant (Monday 09:00 Sydney = 14:00 Perth): 17:30 in Perth is later the same day.
        assertThat(alarms().single().triggerAtMs)
            .isEqualTo(ZonedDateTime.of(2026, 11, 9, 17, 30, 0, 0, perth).toInstant().toEpochMilli())
    }

    @Test
    fun reArmingInsideTheWindowBeforeItFiredKeepsTodayAndArmsAtOnce() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        clock.now = at(9, 17, 33) // the app is opened: MainActivity.onStart re-arms

        scheduler.rearm()

        assertThat(alarms().single().triggerAtMs).isEqualTo(at(9, 17, 33))
    }

    @Test
    fun reArmingAfterItFiredTodayMovesToTomorrow() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        clock.now = at(9, 17, 33)
        status.setLastReviewReminderDate(LocalDate.of(2026, 11, 9))

        scheduler.rearm()

        assertThat(alarms().single().triggerAtMs).isEqualTo(at(10, 17, 30))
    }

    @Test
    fun reArmingPastTheWindowWithoutAFireMovesToTomorrow() = runBlocking<Unit> {
        settings.setReviewReminderEnabled(true)
        clock.now = at(9, 18, 0)

        scheduler.rearm()

        assertThat(alarms().single().triggerAtMs).isEqualTo(at(10, 17, 30))
    }
}
