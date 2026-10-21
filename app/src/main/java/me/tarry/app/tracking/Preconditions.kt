package me.tarry.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** What must hold for scheduled capture to work (ADR 0003). */
data class Preconditions(
    val fineLocation: Boolean = true,
    val backgroundLocation: Boolean = true,
    val locationEnabled: Boolean = true,
    val exactAlarms: Boolean = true,
)

/** Reads [Preconditions] from the platform; faked in tests. */
interface PreconditionChecker {
    fun check(): Preconditions
}

class AndroidPreconditionChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmScheduler: ScheduleAlarmScheduler,
) : PreconditionChecker {
    override fun check(): Preconditions = Preconditions(
        fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        locationEnabled = context.getSystemService(LocationManager::class.java).isLocationEnabled,
        exactAlarms = alarmScheduler.canScheduleExact(),
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
