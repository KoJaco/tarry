package me.tarry.app.tracking

import kotlinx.coroutines.flow.Flow

/** Fused location updates. Collecting registers the request; cancelling the collection removes it. */
interface LocationClient {
    /**
     * Emits batches of fixes for [params]. Fails with [SecurityException] when location permission
     * is missing (including background location when it is required).
     */
    fun updates(params: LocationRequestParams): Flow<List<Fix>>

    /**
     * One user-initiated location request (Tracking health > Test tracking): the freshest fix available within [timeoutMs], no
     * cached one. It records nothing and is never repeated. Never throws for a missing permission or a timeout.
     */
    suspend fun currentFix(priority: LocationPriority, timeoutMs: Long): CurrentFixResult
}

/** The outcome of [LocationClient.currentFix]. */
sealed interface CurrentFixResult {
    data class Success(val fix: Fix) : CurrentFixResult

    /** No fix arrived in time (or the platform could not deliver one). */
    data object Timeout : CurrentFixResult

    /** Location permission is missing. */
    data object Denied : CurrentFixResult
}

/** Activity Recognition transition updates. Results arrive via ActivityTransitionReceiver. */
interface ActivityTransitionClient {
    /** Registers for ENTER transitions. Returns false when unavailable (permission or Play Services). */
    suspend fun register(): Boolean

    suspend fun unregister()
}

/** Exactly one stay geofence at a time; exits arrive via GeofenceExitReceiver. */
interface GeofenceClient {
    /** Replaces any existing stay geofence. */
    suspend fun addStayGeofence(lat: Double, lng: Double, radiusM: Float)

    suspend fun remove()
}

/** Permission facts the capture code needs to pick an [AttentionCause]. */
interface LocationPermissionChecker {
    fun hasFineLocation(): Boolean
}
