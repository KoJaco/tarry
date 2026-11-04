package me.tarry.app.export.summary

import me.tarry.app.summary.BlockPiece
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit

/** English fallback for a place with neither a name nor an address; the PDF passes the localized string instead. */
const val UNNAMED_PLACE = "Unnamed place"

/**
 * The places a block piece was spent at (phase 7 step 5): the names of the non-deleted visits that overlap the piece, in
 * time order, without duplicates. Visits at ignored places and visits with no (known) place are left out. A name is the
 * place's name, then its address, then [unnamedPlace]. Pure: feeds only the PDF.
 */
fun coveredPlaces(
    piece: BlockPiece,
    visits: List<Visit>,
    places: List<Place>,
    unnamedPlace: String = UNNAMED_PLACE,
): List<String> = coveredPlaceEntities(piece, visits, places)
    .map { place -> placeName(place, unnamedPlace) }
    .distinct()

/** A place's display name: its name, then its address, then [unnamedPlace]. */
fun placeName(place: Place, unnamedPlace: String = UNNAMED_PLACE): String =
    place.name?.takeIf { it.isNotBlank() } ?: place.address?.takeIf { it.isNotBlank() } ?: unnamedPlace

/**
 * The places behind [coveredPlaces] as entities (the CSV needs their addresses and centroids): time order, one entry per
 * place, ignored places and visits with no known place left out.
 */
fun coveredPlaceEntities(piece: BlockPiece, visits: List<Visit>, places: List<Place>): List<Place> {
    val byId = places.associateBy { it.id }
    return visits.asSequence()
        .filter { !it.deleted && it.start < piece.end && it.end > piece.start }
        .sortedWith(compareBy({ it.start }, { it.id }))
        .mapNotNull { visit -> visit.placeId?.let { byId[it] } }
        .filter { !it.ignored }
        .distinctBy { it.id }
        .toList()
}
