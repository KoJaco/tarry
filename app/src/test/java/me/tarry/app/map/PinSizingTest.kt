package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinSizingTest {
    private val min = 60_000L

    private fun pin(id: String, number: Int, lat: Double, lng: Double, startMin: Long, lengthMin: Long) =
        VisitPin(id, number, GeoPoint(lat, lng), null, null, start = startMin * min, end = (startMin + lengthMin) * min)

    @Test
    fun everyPinIsTheSameSizeWhateverTheTimeSpent() {
        // The day strip shows how long each visit was; the pins don't repeat it.
        val short = groupCoincidentPins(listOf(pin("a", 1, 1.0, 1.0, 0, 5))).single()
        val long = groupCoincidentPins(listOf(pin("b", 2, 2.0, 2.0, 10, 300))).single()
        assertThat(short.radius).isEqualTo(13f)
        assertThat(long.radius).isEqualTo(13f)
    }

    @Test
    fun aGroupKeepsEveryVisitIdAndALongLabelWidensItsCircle() {
        val group = groupCoincidentPins(listOf(pin("a", 1, 1.0, 1.0, 0, 60), pin("b", 12, 1.0, 1.0, 300, 60))).single()
        assertThat(group.visitIds).containsExactly("a", "b").inOrder()
        assertThat(group.label).isEqualTo("1, 12")
        assertThat(group.radius).isEqualTo(13f + (group.label.length - 2) * 3.5f)
    }

    @Test
    fun drawingOrderPutsWiderGroupsUnderLonePins() {
        val groups = groupCoincidentPins(
            listOf(pin("lone", 1, 1.0, 1.0, 0, 5), pin("pair", 2, 2.0, 2.0, 10, 150), pin("pair2", 3, 2.0, 2.0, 200, 30)),
        )
        assertThat(sortedForDrawing(groups).map { it.first.visitId }).containsExactly("pair", "lone").inOrder()
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
