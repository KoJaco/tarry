package me.tarry.app.ui.summary

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Side insets of the custom range picker's title and headline. The start matches the month headings of the M3
 * date grid (24 dp); the end matches the grid's own side inset (12 dp, `DatePickerHorizontalPadding`), so the title,
 * headline and calendar share their left and right edges.
 */
@Composable
private fun slotPadding(top: Dp = Dp.Hairline, bottom: Dp = Dp.Hairline): PaddingValues =
    PaddingValues(start = TarryTheme.space.xxl, end = TarryTheme.space.m, top = top, bottom = bottom)

/** The picker's small, muted title ("Select dates"), replacing the oversized M3 default. */
@Composable
internal fun RangePickerTitle(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.summary_picker_title),
        style = MaterialTheme.typography.labelMedium,
        color = TarryTheme.colors.muted,
        modifier = modifier.padding(slotPadding(top = TarryTheme.space.l)),
    )
}

/**
 * The selected range as one `titleMedium` line ("26 Oct 2026 – 1 Nov 2026"). Spaces inside each date are
 * non-breaking, so at large font scales it wraps only between the two dates (the dash stays with the first), never mid-date.
 */
@Composable
internal fun RangePickerHeadline(start: LocalDate?, end: LocalDate?, format: DateTimeFormatter, modifier: Modifier = Modifier) {
    val from = (start?.let(format::format) ?: stringResource(R.string.summary_picker_start_placeholder)).noBreak()
    val to = (end?.let(format::format) ?: stringResource(R.string.summary_picker_end_placeholder)).noBreak()
    Text(
        text = stringResource(R.string.summary_picker_range_headline, from, to),
        style = MaterialTheme.typography.titleMedium,
        color = TarryTheme.colors.ink,
        modifier = modifier.padding(slotPadding(bottom = TarryTheme.space.m)),
    )
}

/** Makes every space in a formatted date non-breaking so a line break can only fall between the two dates. */
internal fun String.noBreak(): String = replace(' ', ' ')
