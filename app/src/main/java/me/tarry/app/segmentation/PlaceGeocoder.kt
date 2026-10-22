package me.tarry.app.segmentation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.tarry.segmentation.geo.haversineMeters
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface GeocodeResult {
    /**
     * [distanceM] is how far the address point is from the queried coordinates, when the backend gave one
     * (`Address.hasLatitude()`); the geocoder returns the nearest street address however far away it is.
     * [locality] is the suburb or town, if any.
     */
    data class Found(val address: String, val distanceM: Double? = null, val locality: String? = null) : GeocodeResult

    /** The lookup worked but there is no address for these coordinates. */
    data object NotFound : GeocodeResult

    /** The lookup itself failed (no network, backend error). */
    data class Failed(val retryable: Boolean) : GeocodeResult
}

/** Reverse geocoding. The platform Geocoder is the only network use besides map tiles (AGENTS rule 2). */
interface PlaceGeocoder {
    fun isAvailable(): Boolean

    suspend fun lookup(lat: Double, lng: Double): GeocodeResult
}

/** Address line 0 if present, else thoroughfare, locality and adminArea (non-blank ones) joined with ", "; null if nothing. */
internal fun formatAddress(
    addressLine0: String?,
    thoroughfare: String?,
    locality: String?,
    adminArea: String?,
): String? {
    addressLine0?.takeIf { it.isNotBlank() }?.let { return it }
    return listOf(thoroughfare, locality, adminArea)
        .filter { !it.isNullOrBlank() }
        .joinToString(", ")
        .ifEmpty { null }
}

private fun Address.toResult(lat: Double, lng: Double): GeocodeResult {
    val line0 = if (maxAddressLineIndex >= 0) getAddressLine(0) else null
    val text = formatAddress(line0, thoroughfare, locality, adminArea)
    if (text == null) return GeocodeResult.NotFound
    val distance = if (hasLatitude() && hasLongitude()) haversineMeters(lat, lng, latitude, longitude) else null
    return GeocodeResult.Found(text, distance, locality?.takeIf { it.isNotBlank() })
}

@Singleton
class AndroidPlaceGeocoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PlaceGeocoder {
    private val geocoder by lazy { Geocoder(context, Locale.getDefault()) }

    override fun isAvailable(): Boolean = Geocoder.isPresent()

    override suspend fun lookup(lat: Double, lng: Double): GeocodeResult =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lookupAsync(lat, lng) else lookupBlocking(lat, lng)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun lookupAsync(lat: Double, lng: Double): GeocodeResult = suspendCancellableCoroutine { cont ->
        geocoder.getFromLocation(
            lat, lng, 1,
            object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (cont.isActive) cont.resume(addresses.firstOrNull()?.toResult(lat, lng) ?: GeocodeResult.NotFound)
                }

                override fun onError(errorMessage: String?) {
                    if (cont.isActive) cont.resume(GeocodeResult.Failed(retryable = true))
                }
            },
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun lookupBlocking(lat: Double, lng: Double): GeocodeResult = withContext(Dispatchers.IO) {
        try {
            geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.toResult(lat, lng) ?: GeocodeResult.NotFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            GeocodeResult.Failed(retryable = true)
        }
    }
}
