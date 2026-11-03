package me.tarry.app.export.summary

import com.google.common.truth.Truth.assertThat
import me.tarry.app.summary.BlockPiece
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin
import org.junit.Test
import java.time.LocalDate

class CoveredPlacesTest {
    private val hour = 3_600_000L
    private val piece = BlockPiece("b", LocalDate.of(2025, 11, 10), 9 * hour, 12 * hour, true, null, 3 * hour)

    private fun place(id: String, name: String? = null, address: String? = null, ignored: Boolean = false) =
        Place(id = id, centroidLat = 0.0, centroidLng = 0.0, name = name, address = address, ignored = ignored, updatedAt = 0)

    private fun visit(id: String, placeId: String?, fromH: Long, toH: Long, deleted: Boolean = false) = Visit(
        id = id, placeId = placeId, start = fromH * hour, end = toH * hour, zoneId = "UTC", origin = VisitOrigin.DETECTED,
        confidence = 1f, updatedAt = 0, deleted = deleted,
    )

    private val places = listOf(place("a", name = "Site A"), place("b", name = "Site B"))

    @Test
    fun overlappingVisits_inTimeOrder() {
        val visits = listOf(visit("2", "b", 10, 11), visit("1", "a", 8, 9 + 1))
        assertThat(coveredPlaces(piece, visits, places)).containsExactly("Site A", "Site B").inOrder()
    }

    @Test
    fun visitsOutsideThePiece_andTouchingOnly_areLeftOut() {
        val visits = listOf(visit("1", "a", 7, 9), visit("2", "b", 12, 14))
        assertThat(coveredPlaces(piece, visits, places)).isEmpty()
    }

    @Test
    fun duplicates_appearOnce() {
        val visits = listOf(visit("1", "a", 9, 10), visit("2", "b", 10, 11), visit("3", "a", 11, 12))
        assertThat(coveredPlaces(piece, visits, places)).containsExactly("Site A", "Site B").inOrder()
    }

    @Test
    fun ignoredPlaces_deletedVisits_andVisitsWithoutAPlace_areLeftOut() {
        val all = places + place("i", name = "Home", ignored = true)
        val visits = listOf(
            visit("1", "i", 9, 10), visit("2", "a", 10, 11, deleted = true), visit("3", null, 10, 11), visit("4", "gone", 10, 11),
            visit("5", "b", 11, 12),
        )
        assertThat(coveredPlaces(piece, visits, all)).containsExactly("Site B")
    }

    @Test
    fun theName_fallsBackToTheAddress_thenToUnnamed() {
        val all = listOf(place("n", name = "Named", address = "1 Main St"), place("a", address = "2 Side St"), place("u"), place("blank", name = " "))
        val visits = listOf(visit("1", "n", 9, 10), visit("2", "a", 10, 11), visit("3", "u", 11, 12))
        assertThat(coveredPlaces(piece, visits, all)).containsExactly("Named", "2 Side St", UNNAMED_PLACE).inOrder()
        assertThat(coveredPlaces(piece, listOf(visit("4", "blank", 9, 10)), all, "Sans nom")).containsExactly("Sans nom")
    }

    @Test
    fun entities_areInTimeOrder_oncePerPlace_withoutIgnored() {
        val all = places + place("i", name = "Home", ignored = true)
        val visits = listOf(visit("3", "a", 11, 12), visit("2", "b", 10, 11), visit("1", "a", 9, 10), visit("4", "i", 9, 10))
        assertThat(coveredPlaceEntities(piece, visits, all).map { it.id }).containsExactly("a", "b").inOrder()
    }
}
