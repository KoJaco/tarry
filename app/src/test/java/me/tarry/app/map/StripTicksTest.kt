package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StripTicksTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 21, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun marksAreTheWholeHoursInsideTheSpan() {
        val marks = hourMarks(at(7, 30), at(15, 21), zone)
        assertThat(marks).containsExactlyElementsIn((8..15).map { at(it) }).inOrder()
    }

    @Test
    fun aSpanInsideOneHourHasNoMarks() {
        assertThat(hourMarks(at(7, 10), at(7, 50), zone)).isEmpty()
    }

    @Test
    fun aSpanStartingOnTheHourIncludesIt() {
        assertThat(hourMarks(at(8), at(9), zone)).containsExactly(at(8), at(9)).inOrder()
    }

    @Test
    fun labelsAre12HourOrZeroPadded24Hour() {
        assertThat((8..13).map { hourLabel(at(it), zone, false) }).containsExactly("8", "9", "10", "11", "12", "1").inOrder()
        assertThat(hourLabel(at(0), zone, false)).isEqualTo("12")
        assertThat((8..13).map { hourLabel(at(it), zone, true) }).containsExactly("08", "09", "10", "11", "12", "13").inOrder()
    }

    @Test
    fun labelsThinOutWhenCrowdedOrTextIsLarge() {
        assertThat(hourLabelStride(30f, 1f)).isEqualTo(1)
        assertThat(hourLabelStride(24f, 1f)).isEqualTo(1)
        assertThat(hourLabelStride(23.9f, 1f)).isEqualTo(2)
        assertThat(hourLabelStride(40f, 1.49f)).isEqualTo(1)
        assertThat(hourLabelStride(40f, 1.5f)).isEqualTo(2)
        assertThat(thinned(listOf(1, 2, 3, 4, 5), 2)).containsExactly(1, 3, 5).inOrder()
        assertThat(thinned(listOf(1, 2, 3), 1)).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun aVeryShortBlockIsStillAtLeastTheMinimumWidth() {
        assertThat(blockExtent(100f, 100.5f, 4f)).isEqualTo(100f to 4f)
        assertThat(blockExtent(100f, 160f, 4f)).isEqualTo(100f to 60f)
    }
}
