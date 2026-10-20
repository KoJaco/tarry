package me.tarry.core.data.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Fresh DataStore file per call; its scope is a child of backgroundScope so it dies with the test. */
    private fun TestScope.repo(): SettingsRepository {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val store = createSettingsDataStore(scope) {
            tmp.newFile("settings-${UUID.randomUUID()}.preferences_pb")
        }
        return SettingsRepository(store)
    }

    private inline fun assertRejected(block: () -> Unit) {
        val result = runCatching { block() }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun defaultsMatchSpec() = runTest {
        val s = repo().settings.first()
        assertThat(s.roundingIncrementMin).isEqualTo(0)
        assertThat(s.roundingMode).isEqualTo(RoundingMode.NEAREST)
        assertThat(s.minBillableMinutes).isEqualTo(0)
        assertThat(s.roundDailyTotalPerJob).isFalse()
        assertThat(s.travelAttribution).isEqualTo(TravelAttribution.DESTINATION)
        assertThat(s.rawPingRetentionDays).isEqualTo(90)
        assertThat(s.reviewReminderEnabled).isFalse()
        assertThat(s.reviewReminderMinuteOfDay).isEqualTo(1050)
        assertThat(s.theme).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test
    fun roundingIncrement_updates_andOnlyAffectsItself() = runTest {
        val r = repo()
        for (m in listOf(6, 15, 30, 0)) {
            r.setRoundingIncrementMin(m)
            assertThat(r.settings.first()).isEqualTo(Settings(roundingIncrementMin = m))
        }
    }

    @Test
    fun roundingIncrement_invalidRejected_andStateUnchanged() = runTest {
        val r = repo()
        assertRejected { r.setRoundingIncrementMin(7) }
        assertThat(r.settings.first().roundingIncrementMin).isEqualTo(0)
    }

    @Test
    fun roundingMode_updates() = runTest {
        val r = repo()
        r.setRoundingMode(RoundingMode.UP)
        assertThat(r.settings.first().roundingMode).isEqualTo(RoundingMode.UP)
    }

    @Test
    fun minBillableMinutes_updates_andNegativeRejected() = runTest {
        val r = repo()
        r.setMinBillableMinutes(45)
        assertThat(r.settings.first().minBillableMinutes).isEqualTo(45)
        assertRejected { r.setMinBillableMinutes(-1) }
        assertThat(r.settings.first().minBillableMinutes).isEqualTo(45)
    }

    @Test
    fun roundDailyTotalPerJob_updates() = runTest {
        val r = repo()
        r.setRoundDailyTotalPerJob(true)
        assertThat(r.settings.first().roundDailyTotalPerJob).isTrue()
        r.setRoundDailyTotalPerJob(false)
        assertThat(r.settings.first().roundDailyTotalPerJob).isFalse()
    }

    @Test
    fun travelAttribution_updates() = runTest {
        val r = repo()
        for (a in TravelAttribution.entries) {
            r.setTravelAttribution(a)
            assertThat(r.settings.first().travelAttribution).isEqualTo(a)
        }
    }

    @Test
    fun retention_updates_forever_andBackTo30() = runTest {
        val r = repo()
        r.setRawPingRetentionDays(365)
        assertThat(r.settings.first().rawPingRetentionDays).isEqualTo(365)
        r.setRawPingRetentionDays(null)
        assertThat(r.settings.first().rawPingRetentionDays).isNull()
        r.setRawPingRetentionDays(30)
        assertThat(r.settings.first().rawPingRetentionDays).isEqualTo(30)
    }

    @Test
    fun retention_invalidRejected() = runTest {
        val r = repo()
        assertRejected { r.setRawPingRetentionDays(7) }
        assertRejected { r.setRawPingRetentionDays(0) }
        assertThat(r.settings.first().rawPingRetentionDays).isEqualTo(90)
    }

    @Test
    fun reviewReminder_updates_andBoundsRejected() = runTest {
        val r = repo()
        r.setReviewReminderEnabled(true)
        r.setReviewReminderMinuteOfDay(0)
        assertThat(r.settings.first().reviewReminderEnabled).isTrue()
        assertThat(r.settings.first().reviewReminderMinuteOfDay).isEqualTo(0)
        r.setReviewReminderMinuteOfDay(1439)
        assertThat(r.settings.first().reviewReminderMinuteOfDay).isEqualTo(1439)
        assertRejected { r.setReviewReminderMinuteOfDay(1440) }
        assertRejected { r.setReviewReminderMinuteOfDay(-1) }
        assertThat(r.settings.first().reviewReminderMinuteOfDay).isEqualTo(1439)
    }

    @Test
    fun theme_updates() = runTest {
        val r = repo()
        r.setTheme(ThemeMode.DARK)
        assertThat(r.settings.first().theme).isEqualTo(ThemeMode.DARK)
        r.setTheme(ThemeMode.LIGHT)
        assertThat(r.settings.first().theme).isEqualTo(ThemeMode.LIGHT)
    }

    private fun TestScope.newScope() =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun corruptFile_fallsBackToDefaults_andSettersStillWork() = runTest {
        val file = tmp.newFile("corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55, 0x66.toByte(), 0xff.toByte()))
        val r = SettingsRepository(createSettingsDataStore(newScope()) { file })
        assertThat(r.settings.first()).isEqualTo(Settings())
        r.setTheme(ThemeMode.DARK)
        assertThat(r.settings.first().theme).isEqualTo(ThemeMode.DARK)
    }

    @Test
    fun values_persistAcrossDataStoreInstances() = runTest {
        val file = tmp.newFile("persist.preferences_pb")
        val scope1 = newScope()
        val r1 = SettingsRepository(createSettingsDataStore(scope1) { file })
        r1.setRoundingIncrementMin(15)
        r1.setRoundingMode(RoundingMode.UP)
        r1.setMinBillableMinutes(20)
        r1.setRoundDailyTotalPerJob(true)
        r1.setTravelAttribution(TravelAttribution.entries.last())
        r1.setRawPingRetentionDays(null)
        r1.setReviewReminderEnabled(true)
        r1.setReviewReminderMinuteOfDay(600)
        r1.setTheme(ThemeMode.DARK)
        val expected = r1.settings.first()
        scope1.cancel()

        val r2 = SettingsRepository(createSettingsDataStore(newScope()) { file })
        val actual = r2.settings.first()
        assertThat(actual).isEqualTo(expected)
        assertThat(actual.rawPingRetentionDays).isNull()
        assertThat(actual.roundingIncrementMin).isEqualTo(15)
        assertThat(actual.theme).isEqualTo(ThemeMode.DARK)
    }
}
