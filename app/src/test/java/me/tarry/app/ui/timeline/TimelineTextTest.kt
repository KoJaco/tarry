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

    @Test
    fun shortVisitReadsTimeAndDurationAndLongVisitsKeepTheRange() {
        show(
            1f,
            TimelineItem.Visit("short", at(16, 13), at(16, 19), "Cafe"),
            TimelineItem.Visit("long", at(9), at(11), "Marina"),
        )
        assertThat(texts("timeline_row_short").joinToString("|")).doesNotContain("–")
        assertThat(texts("timeline_row_long").joinToString("|")).contains("9:00–")
    }
}
