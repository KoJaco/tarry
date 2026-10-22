package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisitResizeTest {
    private val min = 60_000L
    private val visit = Span(60 * min, 120 * min)
    private val prev = Span(0, 50 * min)
    private val next = Span(130 * min, 200 * min)
    private val dayStart = 0L
    private val dayEnd = 24 * 60 * min

    private fun end(deltaMin: Double, next: Span? = this.next) =
        resizedEdge(ResizeEdge.End, visit.end, (deltaMin * min).toLong(), visit, prev, next, 5 * min, dayStart, dayEnd)

    private fun start(deltaMin: Double, prev: Span? = this.prev) =
        resizedEdge(ResizeEdge.Start, visit.start, (deltaMin * min).toLong(), visit, prev, next, 5 * min, dayStart, dayEnd)

    @Test
    fun snapsToFiveMinuteSteps() {
        assertThat(end(7.0)).isEqualTo(125 * min)
        assertThat(end(2.4)).isEqualTo(120 * min)
        assertThat(end(2.6)).isEqualTo(125 * min)
        assertThat(start(-8.0)).isEqualTo(50 * min)
    }

    @Test
    fun noMovementKeepsTheExactOriginalEvenOffTheGrid() {
        val off = Span(61 * min, 120 * min)
        assertThat(resizedEdge(ResizeEdge.Start, off.start, 0, off, null, null, 5 * min, dayStart, dayEnd)).isEqualTo(61 * min)
    }

    @Test
    fun keepsTheMinimumLength() {
        assertThat(end(-100.0)).isEqualTo(65 * min)
        assertThat(start(100.0)).isEqualTo(115 * min)
    }

    @Test
    fun stopsWhereATrimmedNeighbourWouldKeepLessThanTheMinimum() {
        assertThat(end(100.0)).isEqualTo(195 * min)
        assertThat(start(-100.0)).isEqualTo(5 * min)
    }

    @Test
    fun aNeighbourAlreadyShorterThanTheMinimumIsNeverTrimmedFurther() {
        assertThat(end(100.0, next = Span(130 * min, 132 * min))).isEqualTo(130 * min)
        assertThat(start(-100.0, prev = Span(46 * min, 50 * min))).isEqualTo(50 * min)
    }

    @Test
    fun staysInsideTheDayWithNoNeighbour() {
        assertThat(end(100_000.0, next = null)).isEqualTo(dayEnd)
        assertThat(start(-100_000.0, prev = null)).isEqualTo(dayStart)
    }
}
