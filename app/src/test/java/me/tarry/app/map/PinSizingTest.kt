package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinSizingTest {
    private val min = 60_000L
    private val hour = 60 * min

    private fun pin(id: String, number: Int, lat: Double, lng: Double, startMin: Long, lengthMin: Long) =
        VisitPin(id, number, GeoPoint(lat, lng), null, null, start = startMin * min, end = (startMin + lengthMin) * min)

    @Test
    fun dwellRadiusMatchesTheReferenceValues() {
        assertThat(dwellRadius(5 * min)).isWithin(0.06f).of(10.0f)
        assertThat(dwellRadius(15 * min)).isWithin(0.06f).of(12.3f)
        assertThat(dwellRadius(30 * min)).isWithin(0.06f).of(14.4f)
        assertThat(dwellRadius(hour)).isWithin(0.06f).of(17.5f)
        assertThat(dwellRadius(150 * min)).isWithin(0.06f).of(23.6f)
        assertThat(dwellRadius(4 * hour)).isEqualTo(28f)
        assertThat(dwellRadius(10 * hour)).isEqualTo(28f)
        assertThat(dwellRadius(0)).isEqualTo(7f)
    }

    @Test
    fun aGroupIsSizedByItsTotalTime() {
        val groups = groupCoincidentPins(listOf(pin("a", 1, 1.0, 1.0, 0, 60), pin("b", 2, 1.0, 1.0, 300, 60)))
        val group = groups.single()
        assertThat(group.durationMs).isEqualTo(2 * hour)
        assertThat(group.radius).isWithin(0.01f).of(dwellRadius(2 * hour))
        assertThat(group.visitIds).containsExactly("a", "b").inOrder()
    }

    @Test
    fun aLongLabelWidensASmallDwellCircle() {
        val group = PinGroup(pin("a", 10, 1.0, 1.0, 0, 5), listOf(10, 11, 12), durationMs = 5 * min)
        assertThat(group.radius).isEqualTo(13f + (group.label.length - 2) * 3.5f)
        assertThat(group.radius).isGreaterThan(dwellRadius(5 * min))
    }

    @Test
    fun aShortLabelNeverWidensTheCircle() {
        val group = groupCoincidentPins(listOf(pin("a", 7, 1.0, 1.0, 0, 5))).single()
        assertThat(group.radius).isEqualTo(dwellRadius(5 * min))
    }

    @Test
    fun drawingOrderIsLargestFirstSoShortStopsSitOnTop() {
        val groups = groupCoincidentPins(
            listOf(pin("short", 1, 1.0, 1.0, 0, 5), pin("long", 2, 2.0, 2.0, 10, 150), pin("mid", 3, 3.0, 3.0, 200, 30)),
        )
        assertThat(sortedForDrawing(groups).map { it.first.visitId }).containsExactly("long", "mid", "short").inOrder()
    }

    @Test
    fun theActiveFlagCoversEveryVisitInTheGroup() {
        val group = groupCoincidentPins(listOf(pin("a", 1, 1.0, 1.0, 0, 5), pin("b", 2, 1.0, 1.0, 10, 5))).single()
        assertThat(group.isActive("b")).isTrue()
        assertThat(group.isActive("a")).isTrue()
        assertThat(group.isActive("c")).isFalse()
        assertThat(group.isActive(null)).isFalse()
    }

    @Test
    fun aTapInsideASmallPinOverABigOneHitsTheSmallOne() {
        val hits = listOf(PinHit("big", 28f, 5f), PinHit("small", 10f, 8f))
        assertThat(pickPin(hits, 1f)).isEqualTo("small")
    }

    @Test
    fun aTapOnlyInTheBigPinHitsTheBigOne() {
        val hits = listOf(PinHit("big", 28f, 20f), PinHit("small", 10f, 25f))
        assertThat(pickPin(hits, 1f)).isEqualTo("big")
    }

    @Test
    fun aTapOutsideBothPicksTheNearestEdge() {
        val hits = listOf(PinHit("a", 10f, 30f), PinHit("b", 20f, 32f))
        assertThat(pickPin(hits, 1f)).isEqualTo("b")
        assertThat(pickPin(emptyList(), 1f)).isNull()
    }
}
