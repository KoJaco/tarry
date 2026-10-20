package me.tarry.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

class PlayLocationClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationClient {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission") // Denial surfaces as SecurityException to the collector.
    override fun updates(params: LocationRequestParams): Flow<List<Fix>> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                trySend(result.locations.map { it.toFix() })
            }
        }
        val request = LocationRequest.Builder(params.priority.toPlayPriority(), params.intervalMs)
            .setMinUpdateDistanceMeters(params.minDisplacementM)
            .apply { if (params.maxUpdateDelayMs > 0) setMaxUpdateDelayMillis(params.maxUpdateDelayMs) }
            .build()
        // A missing permission throws SecurityException here or fails the task; both reach the collector.
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

internal fun LocationPriority.toPlayPriority(): Int = when (this) {
    LocationPriority.PASSIVE -> Priority.PRIORITY_PASSIVE
    LocationPriority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
    LocationPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
}

internal fun Location.toFix(): Fix = Fix(
    lat = latitude,
    lng = longitude,
    // A fix without accuracy cannot be trusted; the ping filter drops it.
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    timeMs = time,
    speedMps = if (hasSpeed()) speed else null,
    bearing = if (hasBearing()) bearing else null,
)
