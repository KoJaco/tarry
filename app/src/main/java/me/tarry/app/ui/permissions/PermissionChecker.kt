package me.tarry.app.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import me.tarry.app.tracking.ScheduleAlarmScheduler
import javax.inject.Inject

/** Reads the current [PermissionSnapshot] from the platform; faked in tests. */
interface PermissionChecker {
    fun check(): PermissionSnapshot
}

class AndroidPermissionChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmScheduler: ScheduleAlarmScheduler,
) : PermissionChecker {
    override fun check(): PermissionSnapshot {
        val notificationsApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        return PermissionSnapshot(
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseLocation = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            activityRecognition = granted(Manifest.permission.ACTIVITY_RECOGNITION),
            notifications = !notificationsApplicable || granted(Manifest.permission.POST_NOTIFICATIONS),
            exactAlarms = alarmScheduler.canScheduleExact(),
            batteryExempt = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            notificationsApplicable = notificationsApplicable,
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Intents for the system screens that grant what a runtime dialog cannot. */
object PermissionIntents {
    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun exactAlarms(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))

    /** Asks the user to exempt Tarry from battery optimisation; capture must survive Doze (ADR 0003). */
    @android.annotation.SuppressLint("BatteryLife")
    fun batteryOptimisation(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
}

/**
 * The system page behind a granted step's "Change" action. Unlike the grant intents, none of these may be a request
 * dialog: the battery request closes at once when the app is already exempt, so Change opens the exemption list instead.
 * Exact alarms need API 31; below that there is no such page and the app details page is the closest.
 */
fun changeIntentFor(step: PermissionStep, context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Intent = when (step) {
    PermissionStep.NOTIFICATIONS -> PermissionIntents.notificationSettings(context)
    PermissionStep.EXACT_ALARMS ->
        if (sdkInt >= Build.VERSION_CODES.S) PermissionIntents.exactAlarms(context) else PermissionIntents.appSettings(context)
    PermissionStep.BATTERY_OPTIMISATION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    PermissionStep.LOCATION,
    PermissionStep.BACKGROUND_LOCATION,
    PermissionStep.ACTIVITY_RECOGNITION,
    -> PermissionIntents.appSettings(context)
}
