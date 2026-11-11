package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.GapCause
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class TimelineTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private val minute = 60_000L

    private fun at(hour: Int, min: Int = 0): Long = date.atTime(hour, min).atZone(zone).toInstant().toEpochMilli()

    private fun parts(is24: Boolean, start: Long, end: Long): String {
        val (a, b) = TimeFormats(Locale.US, is24).rangeParts(start, end, zone)
        return "$a|$b".replace(' ', ' ').replace(' ', ' ')
    }

    @Test
    fun sameHalfDayDropsTheRepeatedMarker() {
        assertThat(parts(false, at(16, 13), at(16, 41))).isEqualTo("4:13|4:41 PM")
        assertThat(parts(false, at(9, 5), at(11, 30))).isEqualTo("9:05|11:30 AM")
    }

    @Test
    fun aLocaleWritingTheMarkerFirstKeepsItOnTheStart() {
        val f = TimeFormats(Locale.SIMPLIFIED_CHINESE, false)
        val (a, b) = f.rangeParts(at(16, 13), at(16, 41), zone)
        val marker = f.time(at(16, 13), zone).substringBefore("4")
        assertThat(marker).isNotEmpty()
        assertThat(a).isEqualTo(f.time(at(16, 13), zone))
        assertThat(b).doesNotContain(marker)
        assertThat(b).contains("4:41")
    }

    @Test
    fun differentHalfDaysKeepBothMarkers() {
        assertThat(parts(false, at(11, 50), at(12, 10))).isEqualTo("11:50 AM|12:10 PM")
    }

    @Test
    fun twentyFourHourIsUnchanged() {
        assertThat(parts(true, at(16, 13), at(16, 41))).isEqualTo("16:13|16:41")
        assertThat(parts(true, at(11, 50), at(12, 10))).isEqualTo("11:50|12:10")
    }

    @Test
    fun shortThresholdIsStrictlyUnderTenMinutes() {
        assertThat(TimelineText.ShortItemMs).isEqualTo(10 * minute)
        assertThat(TimelineText.isShort(10 * minute - 1)).isTrue()
        assertThat(TimelineText.isShort(10 * minute)).isFalse()
        assertThat(TimelineText.isShort(0)).isTrue()
    }

    private fun show(fontScale: Float, vararg items: TimelineItem) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TarryTheme {
                    TimelineContent(date, items.toList(), {}, {}, today = date, zone = zone, nowMs = at(20))
                }
            }
        }
    }

    private fun texts(tag: String): List<String> =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .map { it.text.replace(' ', ' ').replace(' ', ' ') }

    @Test
    fun shortGapRowsReadCompact() = checkGaps(1f)

    @Test
    fun shortGapRowsReadCompactAtFontScale2() = checkGaps(2f)

    private fun checkGaps(scale: Float) {
        run {
            val g = TimelineItem.Gap("g1", at(13), at(13, 1), GapCause.SERVICE_KILLED)
            val u = TimelineItem.Gap("g2", at(14), at(14) + 30_000L, GapCause.SERVICE_KILLED)
            val long = TimelineItem.Gap("g3", at(15), at(15, 10), GapCause.SERVICE_KILLED)
            show(
                scale, g,
                TimelineItem.Visit("v1", at(13, 30), at(13, 50), "A"), u,
                TimelineItem.Visit("v2", at(14, 30), at(14, 50), "B"), long,
            )
            assertThat(texts("timeline_row_g1")).contains("1m gap · App was closed")
            assertThat(texts("timeline_row_g2")).contains("<1m gap · App was closed")
            assertThat(texts("timeline_row_g3")).contains("Not tracked · App was closed")
            // Spoken description keeps the full range.
            composeRule.onNodeWithTag("timeline_row_g1").assertContentDescriptionContains("from 1:00 PM to 1:01 PM", substring = true)
        }
    }

    private fun plain(s: String): String =
        s.replace(Char(0x00A0), ' ').replace(Char(0x202F), ' ').replace(Char(0x200B).toString(), "")

    private fun nextDay(hour: Int, min: Int = 0): Long =
        date.plusDays(1).atTime(hour, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun aRangeClampedAtMidnightKeepsBothMarkers() {
        // 6:00 AM to the next day's 12:00 AM: both ends say AM, but they are on different dates.
        assertThat(parts(false, at(6), nextDay(0))).isEqualTo("6:00 AM|12:00 AM")
        assertThat(parts(false, at(0), nextDay(0))).isEqualTo("12:00 AM|12:00 AM")
    }

    @Test
    fun aRangeOnOneDateStillMerges() {
        assertThat(parts(false, at(0), at(0, 30))).isEqualTo("12:00|12:30 AM")
        assertThat(parts(false, at(22), at(23, 59))).isEqualTo("10:00|11:59 PM")
    }

    /** The range line the timeline would draw for each item (one composition: a rule allows one setContent). */
    private fun rangeTexts(fontScale: Float, vararg items: TimelineItem): List<String> {
        val texts = MutableList(items.size) { "" }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TarryTheme {
                    val (start, end) = dayBounds(date, zone)
                    items.forEachIndexed { i, item -> texts[i] = clampedTimes(item, start, end, zone).range }
                }
            }
        }
        composeRule.waitForIdle()
        return texts.map(::plain)
    }

    @Test
    fun shortVisitReadsTimeAndDurationExactly() {
        val (short, long) = rangeTexts(
            1f,
            TimelineItem.Visit("short", at(16, 13), at(16, 19), "Cafe"),
            TimelineItem.Visit("long", at(9), at(11), "Marina"),
        )
        assertThat(short).isEqualTo("4:13 PM · 6m")
        assertThat(long).isEqualTo("9:00–11:00 AM")
    }

    @Test
    fun visitRangesReadTheSameAtFontScale2() {
        val (short, long) = rangeTexts(
            2f,
            TimelineItem.Visit("short", at(16, 13), at(16, 19), "Cafe"),
            TimelineItem.Visit("long", at(9), at(11), "Marina"),
        )
        assertThat(short).isEqualTo("4:13 PM · 6m")
        assertThat(long).isEqualTo("9:00–11:00 AM")
    }

    @Test
    fun midnightClampedItemsKeepTheirMarkers() {
        val (endsAtMidnight, startedYesterday, longClamped) = rangeTexts(
            1f,
            // Clamped at the end of the day: 11:55 PM to midnight is short, so time and duration plus the note.
            TimelineItem.Visit("a", at(23, 55), nextDay(1), "Night"),
            TimelineItem.Visit("b", at(0) - 30 * minute, at(0, 5), "Night"),
            // Long: 6:00 AM to the next midnight keeps AM on both ends.
            TimelineItem.Visit("c", at(6), nextDay(3), "Shift"),
        )
        assertThat(endsAtMidnight).isEqualTo("11:55 PM · 5m (continues)")
        assertThat(startedYesterday).isEqualTo("12:00 AM · 5m (from previous day)")
        assertThat(longClamped).isEqualTo("6:00 AM–12:00 AM (continues)")
    }
}
