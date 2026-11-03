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
): List<String> {
    val byId = places.associateBy { it.id }
    return visits.asSequence()
        .filter { !it.deleted && it.start < piece.end && it.end > piece.start }
        .sortedWith(compareBy({ it.start }, { it.id }))
        .mapNotNull { visit -> visit.placeId?.let { byId[it] } }
        .filter { !it.ignored }
        .map { place -> place.name?.takeIf { it.isNotBlank() } ?: place.address?.takeIf { it.isNotBlank() } ?: unnamedPlace }
        .distinct()
        .toList()
}
