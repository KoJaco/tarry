package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val CellMinHeight = 56.dp
private const val WrappedPerRow = 4

/** The seven days of the week containing [date], starting on the locale's first day. */
internal fun weekOf(date: LocalDate, locale: Locale): List<LocalDate> {
    val first = date.with(WeekFields.of(locale).dayOfWeek(), 1)
    return List(7) { first.plusDays(it.toLong()) }
}

/**
 * Seven equal day cells. The selected day is an `ink` block, today has an accent dot and days after [today]
 * are muted and not selectable. At large font scales the strip wraps onto two rows.
 */
@Composable
fun WeekStrip(
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val days = weekOf(selected, locale)
    val perRow = if (LocalDensity.current.fontScale >= WrapFontScale) WrappedPerRow else 7
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
        for (row in days.chunked(perRow)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
                for (day in row) {
                    DayCell(day, day == selected, day == today, day > today, locale, onSelect, Modifier.weight(1f))
                }
                // Keep the cells of a short last row the same width as the full rows.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    future: Boolean,
    locale: Locale,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val c = TarryTheme.colors
    val full = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    val description = if (isToday) stringResource(R.string.timeline_day_today, full) else full
    val textColor = when {
        selected -> c.onInk
        future -> c.muted
        else -> c.ink
    }
    Column(
        modifier = modifier
            .heightIn(min = CellMinHeight)
            .clip(TarryTheme.shapes.block)
            .background(if (selected) c.ink else c.paper)
            .selectable(selected = selected, enabled = !future, role = Role.Tab, onClick = { onSelect(day) })
            .semantics { contentDescription = description }
            .testTag("timeline_week_$day"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Visuals only; the cell's description above is what is spoken.
        Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) c.onInk else c.muted,
            )
            Text(text = day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = textColor)
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isToday) c.accent else Color.Transparent),
            )
        }
    }
}
