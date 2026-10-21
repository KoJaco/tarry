package me.tarry.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import me.tarry.core.data.model.ActivityType
import javax.inject.Inject

class PlayActivityTransitionClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ActivityTransitionClient {
    private val client by lazy { ActivityRecognition.getClient(context) }

    @SuppressLint("MissingPermission") // Denial surfaces as SecurityException, caught below.
    override suspend fun register(): Boolean = try {
        client.requestActivityTransitionUpdates(request(), TrackingIntents.activityTransitions(context)).awaitDone()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // SecurityException (permission denied) or ApiException: the machine falls back to distance gating.
        false
    }

    @SuppressLint("MissingPermission") // Denial surfaces as SecurityException, caught below.
    override suspend fun unregister() {
        try {
            client.removeActivityTransitionUpdates(TrackingIntents.activityTransitions(context)).awaitDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing registered or permission gone; there is nothing left to remove.
        }
    }

    private fun request() = ActivityTransitionRequest(
        WATCHED_ACTIVITIES.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        },
    )

    private companion object {
        val WATCHED_ACTIVITIES = listOf(
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
        )
    }
}

internal fun detectedActivityToType(detectedType: Int): ActivityType = when (detectedType) {
    DetectedActivity.STILL -> ActivityType.STILL
    DetectedActivity.WALKING, DetectedActivity.RUNNING -> ActivityType.WALKING
    DetectedActivity.ON_BICYCLE -> ActivityType.ON_BICYCLE
    DetectedActivity.IN_VEHICLE -> ActivityType.IN_VEHICLE
    else -> ActivityType.UNKNOWN
}
