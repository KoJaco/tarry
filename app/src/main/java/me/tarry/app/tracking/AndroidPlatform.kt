package me.tarry.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import me.tarry.core.data.model.SessionTrigger
import javax.inject.Inject

class AndroidServiceLauncher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ServiceLauncher {
    override fun start(trigger: SessionTrigger) {
        try {
            ContextCompat.startForegroundService(context, TrackingService.startIntent(context, trigger))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                throw ServiceStartDeniedException(e)
            }
            throw e
        }
    }

    override fun stop() {
        context.stopService(TrackingService.stopIntent(context))
    }
}

class AndroidLocationPermissionChecker @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationPermissionChecker {
    override fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
