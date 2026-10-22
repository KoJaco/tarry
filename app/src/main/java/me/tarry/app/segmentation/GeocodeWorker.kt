package me.tarry.app.segmentation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingConfig
import me.tarry.core.data.db.dao.PlaceDao

/**
 * Fills in [me.tarry.core.data.db.entity.Place.address] for places that have none. Empty string means "looked up,
 * nothing found" and is never retried. A failed lookup stops the batch and retries the whole work later.
 */
@HiltWorker
class GeocodeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val geocoder: PlaceGeocoder,
    private val placeDao: PlaceDao,
    private val clock: TrackingClock,
    private val config: TrackingConfig,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!geocoder.isAvailable()) return Result.success()
        for (place in placeDao.getMissingAddress(config.geocodeBatchSize)) {
            val address = when (val result = geocoder.lookup(place.centroidLat, place.centroidLng)) {
                is GeocodeResult.Found -> placeLabel(result, config)
                GeocodeResult.NotFound -> ""
                is GeocodeResult.Failed -> return if (result.retryable) Result.retry() else Result.success()
            }
            placeDao.setAddressIfMissing(place.id, address, clock.nowMs())
        }
        return Result.success()
    }
}

/**
 * The stored label for a place from [found]: the street address when the address point is within
 * [TrackingConfig.addressExactMaxM], "Near <address>" within [TrackingConfig.addressNearMaxM], otherwise
 * "Unnamed place · <locality>" (the geocoder returns the nearest address however far away: boats, bushland, remote
 * sites). An unknown distance keeps the plain address.
 */
internal fun placeLabel(found: GeocodeResult.Found, config: TrackingConfig): String {
    val distance = found.distanceM ?: return found.address
    return when {
        distance <= config.addressExactMaxM -> found.address
        distance <= config.addressNearMaxM -> "Near ${found.address}"
        else -> found.locality?.let { "Unnamed place · $it" } ?: "Unnamed place"
    }
}
