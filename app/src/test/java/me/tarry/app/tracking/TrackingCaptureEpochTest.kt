package me.tarry.app.tracking

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.settings.SettingsRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackingCaptureEpochTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") })
    }
    private var tracked = false
    private val epoch by lazy { TrackingCaptureEpoch(settings, InstallTimeSource { 1_000L }) { tracked } }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun onboardingOpenIsNull() = runBlocking<Unit> {
        settings.setOnboardingStartedAtMs(2_000L)
        assertThat(epoch.notBeforeMs()).isNull()
    }

    @Test
    fun onboardingFinishedIsTheLaterOfInstallAndCompletion() = runBlocking<Unit> {
        settings.setOnboardingStartedAtMs(2_000L)
        settings.setOnboardingCompletedAtMs(3_000L)
        assertThat(epoch.notBeforeMs()).isEqualTo(3_000L)
    }

    @Test
    fun onboardingFinishedBeforeTheInstallTimeUsesTheInstallTime() = runBlocking<Unit> {
        settings.setOnboardingStartedAtMs(500L)
        settings.setOnboardingCompletedAtMs(600L)
        assertThat(epoch.notBeforeMs()).isEqualTo(1_000L)
    }

    @Test
    fun aSilentlySkippedOnboardingUsesTheInstallTime() = runBlocking<Unit> {
        settings.setOnboardingCompletedAtMs(9_000L)
        assertThat(epoch.notBeforeMs()).isEqualTo(1_000L)
    }

    @Test
    fun nothingStampedAndNeverTrackedIsAFreshInstallNotSetUpYet() = runBlocking<Unit> {
        assertThat(epoch.notBeforeMs()).isNull()
    }

    @Test
    fun nothingStampedButTrackedBeforeIsAnUpgraderBoundedByItsInstallTime() = runBlocking<Unit> {
        tracked = true
        assertThat(epoch.notBeforeMs()).isEqualTo(1_000L)
    }
}
