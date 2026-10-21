package me.tarry.app.ui.timeline

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import me.tarry.app.R
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.TarryTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Above this font scale the buttons drop below the date (and the week strip wraps) instead of squeezing it. */
internal const val WrapFontScale = 1.5f

/**
 * Weekday over the date on the left, previous / next / calendar buttons on the right. Next is disabled on
 * [today]. At large font scales the buttons wrap below the date.
 */
@Composable
fun DayHeader(
    date: LocalDate,
    today: LocalDate,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    /** The "+" fallback entry for a manual visit; the caller picks the slot. */
    onAddVisit: () -> Unit = {},
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    val pattern = DateFormat.getBestDateTimePattern(locale, if (date.year == today.year) "dMMMM" else "dMMMMy")
    val dateText = date.format(DateTimeFormatter.ofPattern(pattern, locale))
    val wrap = LocalDensity.current.fontScale >= WrapFontScale

    val titles: @Composable (Modifier) -> Unit = { m ->
        Column(modifier = m) {
            Text(weekday, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
            Text(
                text = dateText,
                style = MaterialTheme.typography.displaySmall,
                color = TarryTheme.colors.ink,
                modifier = Modifier
                    .semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    }
                    .testTag("timeline_date"),
            )
        }
    }
    val buttons: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
            IconCircleButton(
                icon = R.drawable.ic_chevron_left,
                contentDescription = stringResource(R.string.timeline_previous_day),
                onClick = onPreviousDay,
                modifier = Modifier.testTag("timeline_previous"),
            )
            IconCircleButton(
                icon = R.drawable.ic_chevron_right,
                contentDescription = stringResource(R.string.timeline_next_day),
                onClick = onNextDay,
                enabled = date < today,
                modifier = Modifier.testTag("timeline_next"),
            )
            IconCircleButton(
                icon = R.drawable.ic_plus,
                contentDescription = stringResource(R.string.timeline_add_visit),
                onClick = onAddVisit,
                modifier = Modifier.testTag("timeline_add_visit"),
            )
            IconCircleButton(
                icon = R.drawable.ic_calendar,
                contentDescription = stringResource(R.string.timeline_open_calendar),
                onClick = { picking = true },
                modifier = Modifier.testTag("timeline_calendar"),
            )
        }
    }

    if (wrap) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
            titles(Modifier.fillMaxWidth())
            buttons()
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            titles(Modifier.weight(1f))
            buttons()
        }
    }

    if (picking) DayPickerDialog(date, today, onDismiss = { picking = false }, onPick = onSelectDate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(
    date: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val todayEnd = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            // Picker times are UTC midnights; the same "no future days" rule as Next.
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis < todayEnd
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TertiaryButton(
                text = stringResource(R.string.timeline_date_picker_ok),
                onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    onDismiss()
                },
            )
        },
        dismissButton = {
            TertiaryButton(text = stringResource(R.string.timeline_date_picker_cancel), onClick = onDismiss)
        },
    ) {
        // The edit-mode toggle is hidden: M3 rebuilds the calendar grid on every mode switch (rows animate in); a day pick is all we need.
        DatePicker(state = state, showModeToggle = false)
    }
}
