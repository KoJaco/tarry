package me.tarry.app.segmentation

import me.tarry.core.data.db.dao.LocationPingDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.dao.VisitDao
import me.tarry.core.data.repository.PlaceLimits
import me.tarry.segmentation.geo.haversineMeters
import javax.inject.Inject

/** A place's centre, in degrees. */
data class PreviewCentre(val lat: Double, val lng: Double)

/**
 * A live visit for the radius preview. Visits store no coordinates, so [lat]/[lng] are the mean of the pings in the
 * visit's span (null when it has none).
 */
data class PreviewVisit(val placeId: String, val frozen: Boolean, val lat: Double?, val lng: Double?)

/** Everything [radiusCounts] needs: this place's centre, the centres of the neighbours, and their live visits. */
data class RadiusPreviewInput(
    val placeId: String,
    val centre: PreviewCentre,
    val neighbourCentres: Map<String, PreviewCentre>,
    val visits: List<PreviewVisit>,
)

/**
 * What a radius would do to history. [stay] and [leave] are this place's detected visits; [join] are detected visits of
 * nearby places that would move here; [frozen] are this place's edited visits, which never change.
 */
data class RadiusCounts(val stay: Int, val leave: Int, val join: Int, val frozen: Int)

/** Pure counts for [radiusM]; no I/O. A neighbour's visit joins when it is within the radius and closer to this centre than to its own. */
fun radiusCounts(input: RadiusPreviewInput, radiusM: Float): RadiusCounts {
    var stay = 0
    var leave = 0
    var join = 0
    var frozen = 0
    for (v in input.visits) {
        if (v.placeId == input.placeId) {
            if (v.frozen) {
                frozen++
                continue
            }
            val d = v.distanceTo(input.centre) ?: continue
            if (d <= radiusM) stay++ else leave++
        } else if (!v.frozen) {
            val own = input.neighbourCentres[v.placeId] ?: continue
            val d = v.distanceTo(input.centre) ?: continue
            val dOwn = v.distanceTo(own) ?: continue
            if (d <= radiusM && d < dOwn) join++
        }
    }
    return RadiusCounts(stay, leave, join, frozen)
}

private fun PreviewVisit.distanceTo(c: PreviewCentre): Double? =
    if (lat == null || lng == null) null else haversineMeters(lat, lng, c.lat, c.lng)

/** Reads the visits and pings a [RadiusPreviewInput] needs. Visits within reach: this place's and its neighbours' (centres within `RADIUS_MAX_M + neighbour radius`). */
class PlaceRadiusPreviewLoader @Inject constructor(
    private val places: PlaceDao,
    private val visits: VisitDao,
    private val pings: LocationPingDao,
) {
    suspend fun load(placeId: String): RadiusPreviewInput? {
        val place = places.getById(placeId)?.takeUnless { it.deleted } ?: return null
        val neighbours = places.getAllActive().filter {
            it.id != place.id && it.mergedIntoId == null &&
                haversineMeters(place.centroidLat, place.centroidLng, it.centroidLat, it.centroidLng) <= PlaceLimits.RADIUS_MAX_M + it.radiusM
        }
        val rows = (listOf(place.id) + neighbours.map { it.id }).flatMap { id ->
            visits.getLiveByPlace(id).map { v ->
                val ps = pings.getInRange(v.start, v.end)
                PreviewVisit(id, v.frozen, ps.takeIf { it.isNotEmpty() }?.map { it.lat }?.average(), ps.takeIf { it.isNotEmpty() }?.map { it.lng }?.average())
            }
        }
        return RadiusPreviewInput(
            placeId = place.id,
            centre = PreviewCentre(place.centroidLat, place.centroidLng),
            neighbourCentres = neighbours.associate { it.id to PreviewCentre(it.centroidLat, it.centroidLng) },
            visits = rows,
        )
    }
}
