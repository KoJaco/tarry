package me.tarry.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class PlayGeofenceClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : GeofenceClient {
    private val client by lazy { LocationServices.getGeofencingClient(context) }

    @SuppressLint("MissingPermission") // Denial surfaces as SecurityException; the coordinator absorbs it.
    override suspend fun addStayGeofence(lat: Double, lng: Double, radiusM: Float) {
        remove()
        val geofence = Geofence.Builder()
            .setRequestId(STAY_GEOFENCE_ID)
            .setCircularRegion(lat, lng, radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        client.addGeofences(request, TrackingIntents.geofenceExit(context)).awaitDone()
    }

    override suspend fun remove() {
        try {
            client.removeGeofences(TrackingIntents.geofenceExit(context)).awaitDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing registered, or permission gone; there is nothing left to remove.
        }
    }

    private companion object {
        const val STAY_GEOFENCE_ID = "stay"
    }
}
