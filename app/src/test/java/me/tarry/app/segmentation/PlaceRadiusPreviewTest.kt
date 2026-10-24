package me.tarry.app.segmentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaceRadiusPreviewTest {
    private val home = PreviewCentre(-33.0, 151.0)

    /** About [m] metres north of [home]. */
    private fun north(m: Double) = PreviewCentre(home.lat + m / 111_195.0, home.lng)

    private fun visit(place: String, m: Double, frozen: Boolean = false) =
        north(m).let { PreviewVisit(place, frozen, it.lat, it.lng) }

    private fun input(vararg visits: PreviewVisit, neighbours: Map<String, PreviewCentre> = emptyMap()) =
        RadiusPreviewInput("home", home, neighbours, visits.toList())

    @Test
    fun ownDetectedVisitsStayOrLeaveByDistance() {
        val i = input(visit("home", 10.0), visit("home", 50.0), visit("home", 90.0))

        assertThat(radiusCounts(i, 60f)).isEqualTo(RadiusCounts(stay = 2, leave = 1, join = 0, frozen = 0))
        assertThat(radiusCounts(i, 100f)).isEqualTo(RadiusCounts(stay = 3, leave = 0, join = 0, frozen = 0))
        assertThat(radiusCounts(i, 20f)).isEqualTo(RadiusCounts(stay = 1, leave = 2, join = 0, frozen = 0))
    }

    @Test
    fun editedVisitsAreCountedAndNeverStayOrLeave() {
        val i = input(visit("home", 10.0, frozen = true), visit("home", 250.0, frozen = true), visit("home", 10.0))

        assertThat(radiusCounts(i, 20f)).isEqualTo(RadiusCounts(stay = 1, leave = 0, join = 0, frozen = 2))
    }

    @Test
    fun aNeighboursVisitJoinsOnlyWhenInsideAndCloserToThisPlaceThanToItsOwn() {
        // The neighbour is 100 m north; its visits at 60 m (closer to home (30 m)) and 90 m (closer to the neighbour).
        val i = input(visit("n", 30.0), visit("n", 90.0), neighbours = mapOf("n" to north(100.0)))

        assertThat(radiusCounts(i, 70f).join).isEqualTo(1)
        assertThat(radiusCounts(i, 100f).join).isEqualTo(1)
        assertThat(radiusCounts(i, 20f).join).isEqualTo(0)
    }

    @Test
    fun aNeighboursEditedVisitNeverJoins() {
        val i = input(visit("n", 30.0, frozen = true), neighbours = mapOf("n" to north(100.0)))

        assertThat(radiusCounts(i, 100f)).isEqualTo(RadiusCounts(0, 0, 0, 0))
    }

    @Test
    fun visitsWithoutPingsAreSkipped() {
        val i = input(PreviewVisit("home", false, null, null), PreviewVisit("n", false, null, null), neighbours = mapOf("n" to north(100.0)))

        assertThat(radiusCounts(i, 100f)).isEqualTo(RadiusCounts(0, 0, 0, 0))
    }
}
