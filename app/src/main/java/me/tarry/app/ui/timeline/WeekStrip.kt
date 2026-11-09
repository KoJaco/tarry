package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
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

/** From this font scale the weekday label is the two-letter form. */
internal const val NarrowFontScale = 1.5f

/**
 * "Mon" style label (trailing "." stripped; short form if the standalone one is a number), or with [narrow] its first two
 * letters ("Mo"): one narrow letter is ambiguous (T, T / S, S) and the locale's short name gives two that are not.
 */
internal fun weekdayLabel(dow: java.time.DayOfWeek, locale: Locale, narrow: Boolean): String {
    val standalone = dow.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
    val name = (if (standalone.any { it.isDigit() }) dow.getDisplayName(TextStyle.SHORT, locale) else standalone).trimEnd('.')
    if (!narrow) return name
    val end = name.offsetByCodePoints(0, minOf(2, name.codePointCount(0, name.length)))
    return name.substring(0, end)
}

/** The seven days of the week containing [date], starting on the locale's first day. */
internal fun weekOf(date: LocalDate, locale: Locale): List<LocalDate> {
    val first = date.with(WeekFields.of(locale).dayOfWeek(), 1)
    return List(7) { first.plusDays(it.toLong()) }
}

/**
 * Least width of a day cell (the strip is 7 of them and 6 gaps). At font scale 1 it is the plain 40 dp the equal cells have
 * always had; above 1 the cell must hold the larger day number, so it grows with the font (never below a 48 dp touch target).
 */
internal fun weekCellMinWidth(fontScale: Float): Dp =
    if (fontScale <= 1f) 40.dp else maxOf(48.dp, (24f + 16f * fontScale).dp)

/** True when seven cells at [weekCellMinWidth] and the gaps between them fit in [available]. */
internal fun weekFits(available: Dp, fontScale: Float, gap: Dp): Boolean = available >= weekCellMinWidth(fontScale) * 7 + gap * 6

/**
 * Seven day cells in one row, never wrapped. The selected day is an `ink` block, today has an accent dot and days after
 * [today] are muted and not selectable. When seven cells do not fit (large font scales) the row scrolls sideways and
 * keeps the selected day in view; otherwise the cells share the width equally.
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
    val fontScale = LocalDensity.current.fontScale
    val gap = TarryTheme.space.xs
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fits = weekFits(maxWidth, fontScale, gap)
        val cellWidth = weekCellMinWidth(fontScale)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        if (!fits) {
            // Keep the selected day in view (centred as far as the ends allow), also when the week or the font changes.
            LaunchedEffect(selected, cellWidth, maxWidth) {
                val index = days.indexOf(selected).coerceAtLeast(0)
                val cell = with(density) { cellWidth.toPx() }
                val step = cell + with(density) { gap.toPx() }
                val view = with(density) { maxWidth.toPx() }
                scroll.scrollTo((index * step - (view - cell) / 2).toInt().coerceAtLeast(0))
            }
        }
        Row(
            Modifier.fillMaxWidth().then(if (fits) Modifier else Modifier.horizontalScroll(scroll)).selectableGroup().testTag("timeline_week_strip"),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (day in days) {
                DayCell(day, day == selected, day == today, day > today, locale, onSelect, if (fits) Modifier.weight(1f) else Modifier.width(cellWidth))
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
    val fontScale = LocalDensity.current.fontScale
    val label = weekdayLabel(day.dayOfWeek, locale, narrow = fontScale >= NarrowFontScale)
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
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) c.onInk else c.muted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
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
