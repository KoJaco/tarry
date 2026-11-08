package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.TripMode
import org.junit.Test

class TripConnectorTest {
    private fun visit(id: String, top: Float, height: Float) =
        PlacedBlock(TimelineItem.Visit(id, 0, 1, id), top, height, compact = false, foldedTrips = emptyList())

    private fun trip(top: Float, height: Float) = PlacedTrip(TimelineItem.Trip("t", 0, 1, 100f, TripMode.VEHICLE), top, height)

    @Test
    fun runsFromThePreviousVisitsBottomToTheNextVisitsTop() {
        val blocks = listOf(visit("a", 0f, 100f), visit("b", 300f, 100f))
        val range = tripConnector(trip(120f, 160f), blocks)
        assertThat(range.start).isEqualTo(100f)
        assertThat(range.endInclusive).isEqualTo(300f)
    }

    @Test
    fun neverShorterThanTheTripsOwnSlot() {
        val blocks = listOf(visit("a", 0f, 130f), visit("b", 250f, 100f))
        val range = tripConnector(trip(120f, 140f), blocks)
        assertThat(range.start).isEqualTo(120f)
        assertThat(range.endInclusive).isEqualTo(260f)
    }

    @Test
    fun withoutNeighboursItIsTheSlotAlone() {
        val range = tripConnector(trip(50f, 80f), emptyList())
        assertThat(range.start).isEqualTo(50f)
        assertThat(range.endInclusive).isEqualTo(130f)
    }

    @Test
    fun gapsAreNotVisitsSoTheyDoNotEndTheLine() {
        val gap = PlacedBlock(TimelineItem.Gap("g", 0, 1, me.tarry.core.data.model.GapCause.NO_FIX), 0f, 100f, compact = false, foldedTrips = emptyList())
        val range = tripConnector(trip(120f, 60f), listOf(gap, visit("b", 300f, 50f)))
        assertThat(range.start).isEqualTo(120f)
        assertThat(range.endInclusive).isEqualTo(300f)
    }
}
