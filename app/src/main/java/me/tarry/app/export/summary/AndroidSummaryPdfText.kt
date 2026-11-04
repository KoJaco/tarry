package me.tarry.app.export.summary

import android.content.Context
import android.text.format.DateFormat
import me.tarry.app.R
import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.SummaryRange
import me.tarry.app.ui.timeline.TimeFormats
import me.tarry.core.data.settings.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The PDF's words from the app's string resources, in the phone's language. Durations and time ranges read as on the
 * Summary screen ("2 h 15 min", "7:30 am–12:05 pm").
 */
internal class AndroidSummaryPdfText(context: Context) : SummaryPdfText {
    private val res = context.resources
    private val locale = res.configuration.locales[0]
    private val times = TimeFormats(locale, DateFormat.is24HourFormat(context))

    private fun formatter(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val dayShort = formatter("EEEdMMM")
    private val dayYear = formatter("EEEdMMMy")
    private val dateYear = formatter("dMMMy")

    override val title = res.getString(R.string.summary_pdf_title)
    override val unnamedPlace = res.getString(R.string.summary_pdf_unnamed_place)
    override val coverJob = res.getString(R.string.summary_pdf_col_job)
    override val coverBlocks = res.getString(R.string.summary_pdf_col_blocks)
    override val coverTotal = res.getString(R.string.summary_pdf_col_total)
    override val coverBilled = res.getString(R.string.summary_pdf_col_billed)
    override val colDate = res.getString(R.string.summary_pdf_col_date)
    override val colTime = res.getString(R.string.summary_pdf_col_time)
    override val colDuration = res.getString(R.string.summary_pdf_col_duration)
    override val colBilled = res.getString(R.string.summary_pdf_col_billed)
    override val colPlaces = res.getString(R.string.summary_pdf_col_places)
    override val colNotes = res.getString(R.string.summary_pdf_col_notes)

    override fun rangeLong(range: SummaryRange): String =
        if (range.from == range.to) {
            dayYear.format(range.from)
        } else {
            res.getString(R.string.summary_range_from_to, dayShort.format(range.from), dayYear.format(range.to))
        }

    override fun rangeShort(range: SummaryRange): String =
        if (range.from == range.to) {
            dateYear.format(range.from)
        } else {
            res.getString(R.string.summary_range_from_to, dateYear.format(range.from), dateYear.format(range.to))
        }

    override fun rules(rules: BillingRules): String {
        val rounding = when {
            rules.incrementMin <= 0 -> res.getString(R.string.summary_pdf_rules_none)
            rules.mode == RoundingMode.UP -> res.getString(R.string.summary_pdf_rules_up, rules.incrementMin)
            else -> res.getString(R.string.summary_pdf_rules_nearest, rules.incrementMin)
        }
        val minimum = rules.minimumMin.takeIf { it > 0 }?.let { res.getString(R.string.summary_pdf_rules_minimum, it) }
        val scope = res.getString(if (rules.perDay) R.string.summary_pdf_rules_per_day else R.string.summary_pdf_rules_per_block)
        return listOfNotNull(rounding, minimum, scope).joinToString(", ")
    }

    override fun jobName(name: String, archived: Boolean): String =
        if (archived) res.getString(R.string.summary_job_archived, name) else name

    override fun continued(name: String): String = res.getString(R.string.summary_pdf_continued, name)

    override fun blocks(count: Int): String = res.getQuantityString(R.plurals.summary_blocks, count, count)

    override fun duration(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(0)
        val hours = (totalMin / 60).toInt()
        val minutes = (totalMin % 60).toInt()
        return when {
            ms <= 0L -> res.getString(R.string.summary_duration_zero)
            hours > 0 && minutes > 0 -> res.getString(R.string.timeline_duration_h_min, hours, minutes)
            hours > 0 -> res.getString(R.string.timeline_duration_h, hours)
            minutes > 0 -> res.getString(R.string.timeline_duration_min, minutes)
            else -> res.getString(R.string.timeline_duration_less_than_min)
        }
    }

    override fun date(date: LocalDate): String = dayShort.format(date)

    override fun timeRange(start: Long, end: Long, zone: ZoneId): String =
        res.getString(R.string.timeline_time_range, times.time(start, zone), times.time(end, zone))

    override fun totals(billedMs: Long, onClockMs: Long): String =
        res.getString(R.string.summary_totals, duration(billedMs), duration(onClockMs))

    override fun unlabelled(onSiteMs: Long, travelMs: Long): String =
        res.getString(R.string.summary_pdf_unlabelled, duration(onSiteMs), duration(travelMs))

    override fun jobTotals(billedMs: Long, totalMs: Long, billableMs: Long): String =
        res.getString(R.string.summary_pdf_job_totals, duration(billedMs), duration(totalMs), duration(billableMs))

    override fun dayTotal(billedMs: Long): String = res.getString(R.string.summary_pdf_day_total, duration(billedMs))

    override fun notBillable(): String = res.getString(R.string.summary_piece_not_billable)

    override fun generatedDate(date: LocalDate): String = dateYear.format(date)

    override fun footer(range: SummaryRange, generated: LocalDate, page: Int, pages: Int): String =
        res.getString(R.string.summary_pdf_footer, rangeShort(range), generatedDate(generated), page, pages)
}
