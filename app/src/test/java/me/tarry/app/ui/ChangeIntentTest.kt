package me.tarry.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.changeIntentFor
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChangeIntentTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun change(step: PermissionStep, sdk: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM): Intent =
        changeIntentFor(step, context, sdk)

    private fun Intent.assertAppDetails() {
        assertThat(action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(data).isEqualTo(Uri.fromParts("package", context.packageName, null))
    }

    @Test
    fun locationStepsOpenTheAppDetailsPage() {
        change(PermissionStep.LOCATION).assertAppDetails()
        change(PermissionStep.BACKGROUND_LOCATION).assertAppDetails()
        change(PermissionStep.ACTIVITY_RECOGNITION).assertAppDetails()
    }

    @Test
    fun notificationsOpenTheAppNotificationSettings() {
        val intent = change(PermissionStep.NOTIFICATIONS)
        assertThat(intent.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(intent.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo(context.packageName)
    }

    @Test
    fun exactAlarmsOpenTheAlarmPageOnApi31AndUp() {
        val intent = change(PermissionStep.EXACT_ALARMS, Build.VERSION_CODES.S)
        assertThat(intent.action).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        assertThat(intent.data).isEqualTo(Uri.fromParts("package", context.packageName, null))
        assertThat(change(PermissionStep.EXACT_ALARMS).action).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    }

    @Test
    fun exactAlarmsFallBackToAppDetailsBelowApi31() {
        change(PermissionStep.EXACT_ALARMS, Build.VERSION_CODES.R).assertAppDetails()
        change(PermissionStep.EXACT_ALARMS, Build.VERSION_CODES.Q).assertAppDetails()
    }

    @Test
    fun batteryChangeOpensTheExemptionListNotTheRequestDialog() {
        val intent = change(PermissionStep.BATTERY_OPTIMISATION)
        assertThat(intent.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        assertThat(intent.action).isNotEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    @Test
    fun everyStepHasAnIntent() {
        for (step in PermissionStep.entries) assertThat(change(step).action).isNotNull()
    }
}
