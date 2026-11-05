package me.tarry.app.export.summary

import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.BlockPiece
import me.tarry.app.summary.JobSummary
import me.tarry.app.summary.SummaryRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The Summary PDF's layout (phase 7 step 5), pure Kotlin: turns a [SummarySnapshot] into pages of positioned text runs and
 * rules. Drawing is SummaryPdfRenderer's job; text widths come from a [TextMeasurer], so tests run on the JVM with a fixed
 * width fake. All words come from [SummaryPdfText], so the layout holds no language of its own.
 */

/** A4 portrait in points, with 40 pt margins. */
object PdfPageSpec {
    const val WIDTH = 595f
    const val HEIGHT = 842f
    const val MARGIN = 40f
    const val CONTENT_WIDTH = WIDTH - 2 * MARGIN

    /** Space under the content that the footer lives in. */
    const val FOOTER_RESERVE = 24f
    const val CONTENT_BOTTOM = HEIGHT - MARGIN - FOOTER_RESERVE
}

/** Text sizes in points (owner 2026-11-04); line heights follow from them. */
object PdfFontSize {
    const val TITLE = 18f
    const val HEADING = 15f
    const val TOTALS = 13f
    const val BODY = 10.5f
    const val TABLE_HEADER = 9.5f
    const val FOOTER = 8f
}

/** Text colours as ARGB ints: black for everything but the detail lines, which are dark grey (#444 on white is about 9.7:1). */
private const val BLACK = 0xFF000000.toInt()
private const val DARK_GREY = 0xFF444444.toInt()

enum class PdfTextStyle(val size: Float, val bold: Boolean, val colorArgb: Int = BLACK) {
    TITLE(PdfFontSize.TITLE, true),
    HEADING(PdfFontSize.HEADING, true),
    TOTALS(PdfFontSize.TOTALS, true),
    SUBHEADING(PdfFontSize.BODY, false),
    BODY(PdfFontSize.BODY, false),
    BODY_BOLD(PdfFontSize.BODY, true),
    /** The places and notes lines under a block's number line. */
    DETAIL(PdfFontSize.BODY, false, DARK_GREY),
    TABLE_HEADER(PdfFontSize.TABLE_HEADER, true),
    FOOTER(PdfFontSize.FOOTER, false),
    ;

    val lineHeight: Float get() = size * 1.3f
}

/** Text width in points. The renderer measures with a real `Paint`; tests use a fixed width per character. */
interface TextMeasurer {
    fun width(text: String, style: PdfTextStyle): Float
}

/** One line of text with its left edge at [x], on [baseline]. */
data class PdfRun(val x: Float, val baseline: Float, val text: String, val style: PdfTextStyle)

/** A horizontal line from [x1] to [x2] at [y]. */
data class PdfRule(val x1: Float, val x2: Float, val y: Float, val thickness: Float = 0.5f)

data class PdfPage(val runs: List<PdfRun>, val rules: List<PdfRule>)

/** Every word the PDF says, in the app's language. The renderer implements it over Android resources. */
interface SummaryPdfText {
    val title: String
    val unnamedPlace: String
    val ellipsis: String get() = "…"
    val coverJob: String
    val coverBlocks: String
    val coverTotal: String
    val coverBilled: String
    val colDate: String
    val colTime: String
    val colDuration: String
    val colBilled: String
    val colNotes: String

    /** "Mon 2 Nov – Sun 8 Nov 2026". */
    fun rangeLong(range: SummaryRange): String

    /** "2 Nov 2026 – 8 Nov 2026", for the footer. */
    fun rangeShort(range: SummaryRange): String

    /** The billing rules in words. */
    fun rules(rules: BillingRules): String
    fun jobName(name: String, archived: Boolean): String
    fun continued(name: String): String
    fun blocks(count: Int): String
    fun duration(ms: Long): String
    fun date(date: LocalDate): String
    fun timeRange(start: Long, end: Long, zone: ZoneId): String

    /** "Billed X · on the clock Y". */
    fun totals(billedMs: Long, onClockMs: Long): String

    /** "Unlabelled: on site A · travel B". */
    fun unlabelled(onSiteMs: Long, travelMs: Long): String

    /** "Billed X · total Y · billable Z". */
    fun jobTotals(billedMs: Long, totalMs: Long, billableMs: Long): String

    /** "Day total, billed X". */
    fun dayTotal(billedMs: Long): String
    fun notBillable(): String

    /** The date the PDF was generated, short and with the year ("4 Nov 2026"). */
    fun generatedDate(date: LocalDate): String

    /** "Tarry · <from> – <to> · Generated <date> · Page n of m". */
    fun footer(range: SummaryRange, generated: LocalDate, page: Int, pages: Int): String
}

object SummaryPdfLayout {
    private const val CELL_GAP = 6f
    private const val ROW_PAD = 3f
    private const val RIGHT = PdfPageSpec.WIDTH - PdfPageSpec.MARGIN

    private enum class Align { LEFT, RIGHT }

    private class Column(val header: String, val x: Float, val width: Float, val align: Align = Align.LEFT)

    /** One table row: a cell per column, optional detail lines under them, and an optional trailer (a day total); all stay on one page. */
    private class Row(
        val cells: List<String>,
        val styles: List<PdfTextStyle>,
        val trailer: String? = null,
        /** Full-width lines under the cells (places, then notes); they stay on the row's page. */
        val details: List<String> = emptyList(),
    )

    /** Lays [snapshot] out: the cover, then one section per job in `byJob` order, each page with its footer. */
    fun layout(snapshot: SummarySnapshot, text: SummaryPdfText, measurer: TextMeasurer): List<PdfPage> {
        val builder = Builder(text, measurer)
        builder.cover(snapshot)
        for (job in snapshot.result.byJob) builder.job(snapshot, job)
        return builder.finish(snapshot.range, Instant.ofEpochMilli(snapshot.generatedAt).atZone(snapshot.zone).toLocalDate())
    }

    private class Builder(val text: SummaryPdfText, val measurer: TextMeasurer) {
        private class Page(val runs: MutableList<PdfRun> = mutableListOf(), val rules: MutableList<PdfRule> = mutableListOf())

        private val pages = ArrayList<Page>()
        private var page = Page()
        private var y = PdfPageSpec.MARGIN

        /** What a continuation page opens with, then the table header under it. Null: no table is running. */
        private var continuationTitle: String? = null
        private var columns: List<Column> = emptyList()

        private fun width(s: String, style: PdfTextStyle) = measurer.width(s, style)

        private fun newPage() {
            page = Page()
            pages += page
            y = PdfPageSpec.MARGIN
        }

        fun finish(range: SummaryRange, generated: LocalDate): List<PdfPage> = pages.mapIndexed { index, p ->
            val footer = PdfRun(
                PdfPageSpec.MARGIN, PdfPageSpec.HEIGHT - PdfPageSpec.MARGIN, text.footer(range, generated, index + 1, pages.size), PdfTextStyle.FOOTER,
            )
            PdfPage(p.runs + footer, p.rules.toList())
        }

        /**
         * Greedy word wrap to [maxWidth]; newlines kept; a word wider than the line is broken by characters. Tabs and other
         * control characters become spaces (the font has no glyph for them, they would draw as boxes).
         */
        private fun wrap(value: String, maxWidth: Float, style: PdfTextStyle): List<String> {
            val lines = ArrayList<String>()
            val text = value.replace("\r\n", "\n").replace('\r', '\n').map { if (it != '\n' && it.isISOControl()) ' ' else it }.joinToString("")
            for (paragraph in text.split('\n')) {
                var line = ""
                for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (width(candidate, style) <= maxWidth) {
                        line = candidate
                        continue
                    }
                    if (line.isNotEmpty()) lines += line
                    var rest = word
                    while (width(rest, style) > maxWidth && rest.length > 1) {
                        var n = rest.length - 1
                        while (n > 1 && width(rest.substring(0, n), style) > maxWidth) n--
                        lines += rest.substring(0, n)
                        rest = rest.substring(n)
                    }
                    line = rest
                }
                if (line.isNotEmpty() || paragraph.isEmpty()) lines += line
            }
            return lines.ifEmpty { listOf("") }
        }

        /** [value] cut to [maxWidth] and ended with an ellipsis. */
        private fun withEllipsis(value: String, maxWidth: Float, style: PdfTextStyle): String {
            var s = value.trimEnd()
            while (s.isNotEmpty() && width(s + text.ellipsis, style) > maxWidth) s = s.dropLast(1).trimEnd()
            return s + text.ellipsis
        }

        /** The continuation title as drawn: wrapped, and cut to [MAX_TITLE_LINES] so its height is bounded. Empty: no table is running. */
        private fun continuationLines(): List<String> {
            val title = continuationTitle ?: return emptyList()
            val lines = wrap(title, PdfPageSpec.CONTENT_WIDTH, PdfTextStyle.HEADING)
            if (lines.size <= MAX_TITLE_LINES) return lines
            return lines.take(MAX_TITLE_LINES - 1) + withEllipsis(lines[MAX_TITLE_LINES - 1], PdfPageSpec.CONTENT_WIDTH, PdfTextStyle.HEADING)
        }

        /** Height of what a new page opens with while a table runs: the (wrapped) title, then the table header. */
        private fun continuationHeight(): Float {
            val lines = continuationLines()
            if (lines.isEmpty()) return 0f
            return lines.size * PdfTextStyle.HEADING.lineHeight + CONTINUATION_GAP + PdfTextStyle.TABLE_HEADER.lineHeight + 2 * ROW_PAD
        }

        /**
         * Starts a new page (with the continuation header when a table is running) unless [height] still fits. [row] cuts
         * rows to what fits under [continuationHeight], so after the header [height] fits whenever it came from a row.
         */
        private fun ensure(height: Float) {
            if (y + height <= PdfPageSpec.CONTENT_BOTTOM) return
            newPage()
            val lines = continuationLines()
            if (lines.isEmpty()) return
            for (line in lines) {
                page.runs += PdfRun(PdfPageSpec.MARGIN, y + PdfTextStyle.HEADING.size, line, PdfTextStyle.HEADING)
                y += PdfTextStyle.HEADING.lineHeight
            }
            y += CONTINUATION_GAP
            tableHeader()
        }

        private fun lines(value: String, style: PdfTextStyle, gapAfter: Float = 0f) {
            for (line in wrap(value, PdfPageSpec.CONTENT_WIDTH, style)) {
                ensure(style.lineHeight)
                page.runs += PdfRun(PdfPageSpec.MARGIN, y + style.size, line, style)
                y += style.lineHeight
            }
            y += gapAfter
        }

        private fun cell(column: Column, value: String, style: PdfTextStyle, top: Float, line: Int) {
            val baseline = top + ROW_PAD + line * style.lineHeight + style.size
            val x = if (column.align == Align.RIGHT) column.x + column.width - CELL_GAP - width(value, style) else column.x
            page.runs += PdfRun(x, baseline, value, style)
        }

        private fun tableHeader() {
            val top = y
            for (c in columns) cell(c, c.header, PdfTextStyle.TABLE_HEADER, top, 0)
            y += PdfTextStyle.TABLE_HEADER.lineHeight + 2 * ROW_PAD
            page.rules += PdfRule(PdfPageSpec.MARGIN, RIGHT, y, 0.8f)
        }

        /**
         * Starts a table: its header now, and again under [continued] on every page it spills onto. The section heading is
         * drawn just before, so the first row must start under it: when header and first row do not fit in what is left of
         * the page (a very tall row) and [keepWithHeading] says the heading already stands on a fresh page, the row is cut to what fits instead of breaking the page and orphaning the heading.
         */
        private fun startTable(cols: List<Column>, continued: String, firstRow: Row? = null, keepWithHeading: Boolean = false) {
            columns = cols
            // Measured as it will be drawn on later pages (the continuation header decides how tall a row can get).
            continuationTitle = continued
            val first = firstRow?.let { prepare(it).height } ?: 0f
            // No table is running yet while the header is made to fit, so a page break here does not draw a header of its own.
            continuationTitle = null
            val headerHeight = PdfTextStyle.TABLE_HEADER.lineHeight + 2 * ROW_PAD
            val available = PdfPageSpec.CONTENT_BOTTOM - y - headerHeight
            if (keepWithHeading && firstRow != null && first > available) {
                // Keep with next, capped: the heading, table header and a cut first row share this page.
                firstRowMax = available.coerceAtLeast(0f)
                ensure(headerHeight)
            } else {
                ensure(headerHeight + first)
            }
            continuationTitle = continued
            tableHeader()
        }

        /** Height limit for the first row of the table just started (see [startTable]); consumed by the next [row]. */
        private var firstRowMax: Float? = null

        private data class Prepared(
            val wrapped: List<List<String>>,
            val details: List<String>,
            val numberHeight: Float,
            val cellsHeight: Float,
            val trailerHeight: Float,
        ) {
            /** The whole row: number line, detail lines and trailer. It never splits across pages. */
            val height: Float get() = cellsHeight + trailerHeight
        }

        /** [row] wrapped into the current columns, cut to what a page can hold, with its heights. */
        private fun prepare(row: Row, maxHeight: Float = PdfPageSpec.CONTENT_BOTTOM - PdfPageSpec.MARGIN - continuationHeight()): Prepared {
            val body = PdfTextStyle.BODY
            val detail = PdfTextStyle.DETAIL
            val trailerHeight = if (row.trailer != null) PdfTextStyle.BODY_BOLD.lineHeight + ROW_PAD else 0f
            // The most a row can take: a whole page under the actual continuation header. Taller rows are cut to it.
            val room = maxHeight - 2 * ROW_PAD - trailerHeight
            val maxLines = (room / body.lineHeight).toInt().coerceAtLeast(1)
            // With details the number line may take at most half of that, so the details always get room.
            val numberCap = if (row.details.isEmpty()) maxLines else (maxLines / 2).coerceAtLeast(1)
            val wrapped = columns.mapIndexed { i, c ->
                val lines = wrap(row.cells[i], c.width - CELL_GAP, row.styles[i])
                if (lines.size > numberCap) {
                    lines.take(numberCap - 1) + withEllipsis(lines[numberCap - 1], c.width - CELL_GAP, row.styles[i])
                } else {
                    lines
                }
            }
            val numberHeight = wrapped.maxOf { it.size } * body.lineHeight
            var details = row.details.flatMap { wrap(it, PdfPageSpec.CONTENT_WIDTH, detail) }
            if (details.isNotEmpty()) {
                val budget = ((room - numberHeight - DETAIL_GAP) / detail.lineHeight).toInt().coerceAtLeast(1)
                if (details.size > budget) {
                    details = details.take(budget - 1) + withEllipsis(details[budget - 1], PdfPageSpec.CONTENT_WIDTH, detail)
                }
            }
            val detailHeight = if (details.isEmpty()) 0f else DETAIL_GAP + details.size * detail.lineHeight
            return Prepared(wrapped, details, numberHeight, numberHeight + detailHeight + 2 * ROW_PAD, trailerHeight)
        }

        private fun row(row: Row) {
            val prepared = prepare(row, firstRowMax ?: (PdfPageSpec.CONTENT_BOTTOM - PdfPageSpec.MARGIN - continuationHeight()))
            firstRowMax = null
            ensure(prepared.height)
            val top = y
            columns.forEachIndexed { i, c -> prepared.wrapped[i].forEachIndexed { line, s -> cell(c, s, row.styles[i], top, line) } }
            // Detail lines: full width, under the number line with a little extra space.
            val detailTop = top + ROW_PAD + prepared.numberHeight + DETAIL_GAP
            prepared.details.forEachIndexed { line, s ->
                page.runs += PdfRun(PdfPageSpec.MARGIN, detailTop + line * PdfTextStyle.DETAIL.lineHeight + PdfTextStyle.DETAIL.size, s, PdfTextStyle.DETAIL)
            }
            y += prepared.cellsHeight
            if (row.trailer != null) {
                val style = PdfTextStyle.BODY_BOLD
                val w = width(row.trailer, style)
                page.runs += PdfRun(RIGHT - w, y + style.size - ROW_PAD, row.trailer, style)
                y += prepared.trailerHeight
            }
            page.rules += PdfRule(PdfPageSpec.MARGIN, RIGHT, y, 0.3f)
        }

        fun cover(snapshot: SummarySnapshot) {
            val result = snapshot.result
            newPage()
            lines(text.title, PdfTextStyle.TITLE, gapAfter = 4f)
            lines(text.rangeLong(snapshot.range), PdfTextStyle.SUBHEADING, gapAfter = 2f)
            lines(text.rules(snapshot.rules), PdfTextStyle.BODY, gapAfter = 14f)

            val numeric = 78f
            val w = PdfPageSpec.CONTENT_WIDTH
            val body = PdfTextStyle.BODY
            val rows = result.byJob.map { job ->
                Row(
                    listOf(text.jobName(job.name, job.archived), text.blocks(job.blockCount), text.duration(job.totalMs), text.duration(job.billedMs)),
                    listOf(body, body, body, PdfTextStyle.BODY_BOLD),
                )
            }
            startTable(
                listOf(
                    Column(text.coverJob, PdfPageSpec.MARGIN, w - 3 * numeric),
                    Column(text.coverBlocks, PdfPageSpec.MARGIN + w - 3 * numeric, numeric, Align.RIGHT),
                    Column(text.coverTotal, PdfPageSpec.MARGIN + w - 2 * numeric, numeric, Align.RIGHT),
                    Column(text.coverBilled, PdfPageSpec.MARGIN + w - numeric, numeric, Align.RIGHT),
                ),
                continued = text.continued(text.title),
                firstRow = rows.firstOrNull(),
            )
            rows.forEach { row(it) }
            continuationTitle = null
            y += 14f
            lines(text.totals(result.billedMs, result.onClockMs), PdfTextStyle.TOTALS, gapAfter = 6f)
            if (result.hasUnlabelled) lines(text.unlabelled(result.unlabelledOnSiteMs, result.unlabelledTravelMs), PdfTextStyle.BODY)
        }

        fun job(snapshot: SummarySnapshot, job: JobSummary) {
            val perDay = snapshot.result.perDay
            val name = text.jobName(job.name, job.archived)
            // Four columns spread over the content width; Duration and Billed are right-aligned.
            val widths = listOf(COL_DATE, COL_TIME, COL_DURATION, PdfPageSpec.CONTENT_WIDTH - COL_DATE - COL_TIME - COL_DURATION)
            val xs = widths.runningFold(PdfPageSpec.MARGIN) { acc, wd -> acc + wd }
            val cols = listOf(
                Column(text.colDate, xs[0], widths[0]),
                Column(text.colTime, xs[1], widths[1]),
                Column(text.colDuration, xs[2], widths[2], Align.RIGHT),
                Column(text.colBilled, xs[3], widths[3], Align.RIGHT),
            )
            val rows = job.pieces.mapIndexed { i, piece ->
                val lastOfDay = job.pieces.getOrNull(i + 1)?.date != piece.date
                val trailer = if (perDay && lastOfDay) job.days.firstOrNull { it.date == piece.date }?.let { text.dayTotal(it.billedMs) } else null
                Row(pieceCells(snapshot, piece, perDay), List(4) { PdfTextStyle.BODY }, trailer, pieceDetails(snapshot, piece))
            }

            // Keep with next: the header, subtitle, totals line, table header and first row all fit here, or the section starts a page.
            val headerLines = wrap(name, PdfPageSpec.CONTENT_WIDTH, PdfTextStyle.HEADING).size
            columns = cols
            continuationTitle = text.continued(name)
            val firstRow = rows.firstOrNull()?.let { prepare(it).height } ?: 0f
            continuationTitle = null
            val needed = headerLines * PdfTextStyle.HEADING.lineHeight + 2f + PdfTextStyle.SUBHEADING.lineHeight +
                PdfTextStyle.BODY_BOLD.lineHeight + 10f + PdfTextStyle.TABLE_HEADER.lineHeight + 2 * ROW_PAD + firstRow
            if (y + JOB_GAP + needed > PdfPageSpec.CONTENT_BOTTOM) newPage() else y += JOB_GAP

            lines(name, PdfTextStyle.HEADING, gapAfter = 2f)
            lines(text.rangeLong(snapshot.range), PdfTextStyle.SUBHEADING)
            lines(text.jobTotals(job.billedMs, job.totalMs, job.billableMs), PdfTextStyle.BODY_BOLD, gapAfter = 10f)
            startTable(cols, continued = text.continued(name), firstRow = rows.firstOrNull(), keepWithHeading = true)
            rows.forEach { row(it) }
        }

        /** The lines under a block's number line: the covered places, then "Notes: …"; a line with nothing to say is left out. */
        private fun pieceDetails(snapshot: SummarySnapshot, p: BlockPiece): List<String> = listOfNotNull(
            coveredPlaces(p, snapshot.visits, snapshot.places, text.unnamedPlace).joinToString(", ").takeIf { it.isNotBlank() },
            p.notes?.takeIf { it.isNotBlank() }?.let { "${text.colNotes}: ${it.trim()}" },
        )

        private fun pieceCells(snapshot: SummarySnapshot, p: BlockPiece, perDay: Boolean): List<String> {
            val billed = when {
                !p.billable -> text.notBillable()
                perDay -> ""
                else -> text.duration(p.billedMs ?: 0L)
            }
            return listOf(
                text.date(p.date),
                text.timeRange(p.start, p.end, snapshot.zone),
                text.duration(p.durationMs),
                billed,
            )
        }
    }

    private const val CONTINUATION_GAP = 6f

    /** Space between one job section and what is above it. */
    private const val JOB_GAP = 20f

    // Job table columns in points (Billed takes the rest of the 515 pt): "12:05 pm – 12:05 pm" is about 100 pt wide at 10.5 pt.
    private const val DETAIL_GAP = 4f
    private const val COL_DATE = 110f
    private const val COL_TIME = 175f
    private const val COL_DURATION = 115f
    private const val MAX_TITLE_LINES = 3
}
