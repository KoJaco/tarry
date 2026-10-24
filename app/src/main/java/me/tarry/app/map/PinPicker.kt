package me.tarry.app.map

import java.util.Locale

/** What the pin picker returns: a known place near the pin, or a new place at the pin. */
sealed interface PlacePick {
    data class Known(val placeId: String) : PlacePick

    data class New(val lat: Double, val lng: Double) : PlacePick
}

/** A known place as the picker sees it. [lastVisitAt] is UTC epoch ms, null when never visited. */
data class PickPlace(val id: String, val label: String, val centre: GeoPoint, val lastVisitAt: Long?, val radiusM: Float = 75f)

/** A [PickPlace] within snapping distance of the pin. */
data class NearbyPlace(val place: PickPlace, val distanceM: Double)

/** Pin picker tunables (AGENTS.md rule 5). */
data class PinPickerConfig(
    val startZoom: Double = 16.0,
    /** Used when there is no known place at all: the whole world, so the user can zoom to where they are. */
    val fallbackCentre: GeoPoint = GeoPoint(20.0, 0.0),
    val fallbackZoom: Double = 2.0,
    val snapM: Double = MapConfig().pinSnapM,
)

/**
 * Where the picker's camera starts: the place already chosen in the form, else the most recently visited place, else any
 * place (the first), else null (the caller zooms out to [PinPickerConfig.fallbackCentre]).
 */
fun startCentre(chosen: GeoPoint?, places: List<PickPlace>): GeoPoint? =
    chosen ?: places.filter { it.lastVisitAt != null }.maxByOrNull { it.lastVisitAt!! }?.centre ?: places.firstOrNull()?.centre

/** Places whose centre is within [withinM] of [pin], nearest first. */
fun nearbyPlaces(pin: GeoPoint, places: List<PickPlace>, withinM: Double = MapConfig().pinSnapM): List<NearbyPlace> {
    val byId = places.associateBy { it.id }
    return placesNear(pin, places.map { it.id to it.centre }, withinM).map { (id, d) -> NearbyPlace(byId.getValue(id), d) }
}

/** Five decimal places (about 1 m), locale independent, for the "New place at ..." label. */
fun formatPinCoordinates(lat: Double, lng: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lng)
