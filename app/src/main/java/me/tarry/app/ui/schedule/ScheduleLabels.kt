package me.tarry.app.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import me.tarry.app.R
import me.tarry.app.ui.timeline.weekdayLabel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** "07:00" / "07:30" for a slot index; 48 (the end of the last slot) is "24:00". */
internal fun slotTime(slot: Int): String = "%02d:%02d".format(slot / 2, if (slot % 2 == 0) 0 else 30)

@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** "Monday" in the user's language (the short form is [shortDayName]). */
internal fun dayName(day: DayOfWeek, style: TextStyle, locale: Locale): String = day.getDisplayName(style, locale)

/** "Mon" in the user's language, never with the locale's abbreviation dot ("M." in en-AU): the shared [weekdayLabel]. */
internal fun shortDayName(day: DayOfWeek, locale: Locale): String = weekdayLabel(day, locale, narrow = false)

/** The Settings row subtitle for a schedule: "Mon–Fri 07:00–17:00", "Off" or "Custom". */
@Composable
internal fun scheduleSummaryText(summary: ScheduleSummary): String {
    val locale = currentLocale()
    return when (summary) {
        ScheduleSummary.Off -> stringResource(R.string.schedule_summary_off)
        ScheduleSummary.Custom -> stringResource(R.string.schedule_summary_custom)
        is ScheduleSummary.Uniform -> formatUniform(summary, { shortDayName(it, locale) }, ::slotTime)
    }
}
