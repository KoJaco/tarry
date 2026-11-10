package me.tarry.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
            .setMinUpdateIntervalMillis(params.minUpdateIntervalMs ?: params.intervalMs)
            .setWaitForAccurateLocation(params.waitForAccurateLocation)
            .apply {
                if (params.maxUpdateDelayMs > 0) setMaxUpdateDelayMillis(params.maxUpdateDelayMs)
                params.maxUpdateAgeMs?.let { setMaxUpdateAgeMillis(it) }
            }
            .build()
        // A missing permission throws SecurityException here or fails the task; both reach the collector.
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    // A missing permission surfaces as SecurityException (thrown or as a failed Task) and is the Denied result.
    @SuppressLint("MissingPermission")
    override suspend fun currentFix(priority: LocationPriority, timeoutMs: Long): CurrentFixResult {
        val cancel = CancellationTokenSource()
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(priority.toPlayPriority())
                .setMaxUpdateAgeMillis(0)
                .setDurationMillis(timeoutMs)
                .build()
            // The request ends itself after [timeoutMs]; the outer timeout is only a guard against a Task that never completes.
            val location = withTimeoutOrNull(timeoutMs + GUARD_MS) { client.getCurrentLocation(request, cancel.token).awaitResult() }
            if (location == null) CurrentFixResult.Timeout else CurrentFixResult.Success(location.toFix())
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            CurrentFixResult.Denied
        } catch (e: Exception) {
            // A failed Task wraps the SecurityException (ExecutionException, ApiException): still a denial, not a timeout.
            if (e.hasSecurityCause()) return CurrentFixResult.Denied
            Log.w("PlayLocationClient", "Test fix failed", e)
            CurrentFixResult.Timeout
        } finally {
            // Also runs when the caller is cancelled (the user left the screen): the platform request stops with it.
            cancel.cancel()
        }
    }

    private companion object {
        const val GUARD_MS = 5_000L
    }
}

internal fun LocationPriority.toPlayPriority(): Int = when (this) {
    LocationPriority.PASSIVE -> Priority.PRIORITY_PASSIVE
    LocationPriority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
    LocationPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
}

internal fun Location.toFix(): Fix = Fix(
    lat = latitude,
    lng = longitude,
    // A fix without accuracy cannot be trusted; the ping filter drops it.
    accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    timeMs = time,
    speedMps = if (hasSpeed()) speed else null,
    bearing = if (hasBearing()) bearing else null,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
)

/** True when this throwable or anything in its cause chain is a [SecurityException] (the chain is walked at most 16 deep, so a cycle cannot hang it). */
internal fun Throwable.hasSecurityCause(): Boolean {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < 16) {
        if (t is SecurityException) return true
        t = t.cause
    }
    return false
}
