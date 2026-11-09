package me.tarry.app.ui.sessions

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class SessionRangeFormatTest {
    private val au = Locale.forLanguageTag("en-AU")
    private val zone = "Australia/Sydney"

    private fun ms(day: Int, h: Int, m: Int) =
        ZonedDateTime.of(2026, 11, day, h, m, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    /** Plain spaces, no zero-width break hints, so the wording can be compared whatever ICU version formats the times. */
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ').replace("​", "")

    @Test
    fun sameDayShowsTheDateOnceAndNoSeconds() {
        assertThat(formatSessionRange(ms(6, 16, 14), ms(6, 16, 30), zone, au).plain()).isEqualTo("6 Nov 2026, 4:14 pm–4:30 pm")
    }

    @Test
    fun differentDaysShowBothDates() {
        assertThat(formatSessionRange(ms(6, 23, 50), ms(7, 0, 10), zone, au).plain())
            .isEqualTo("6 Nov 2026, 11:50 pm–7 Nov 2026, 12:10 am")
    }

    @Test
    fun theOnlyBreakIsAfterTheDash() {
        val text = formatSessionRange(ms(6, 16, 14), ms(7, 16, 30), zone, au)
        assertThat(text).contains("–​")
        // Every other gap is a no-break space; the commas are followed by one too.
        assertThat(text.replace("–​", "").contains(' ')).isFalse()
    }

    @Test
    fun theStartOfAnOpenSessionIsOneUnbrokenDateAndTime() {
        assertThat(formatSessionStart(ms(6, 16, 14), zone, au).plain()).isEqualTo("6 Nov 2026, 4:14 pm")
        assertThat(formatSessionStart(ms(6, 16, 14), zone, au)).doesNotContain(" ")
    }
}
