package me.tarry.app.export.summary

import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.BlockPiece
import me.tarry.app.summary.JobSummary
import me.tarry.app.summary.SummaryRange
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

enum class PdfTextStyle(val size: Float, val bold: Boolean) {
    TITLE(22f, true),
    HEADING(15f, true),
    SUBHEADING(10.5f, false),
    BODY(9f, false),
    BODY_BOLD(9f, true),
    TABLE_HEADER(8.5f, true),
    FOOTER(8f, false),
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
    val colPlaces: String
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

    /** "Tarry · <from> – <to> · Page n of m". */
    fun footer(range: SummaryRange, page: Int, pages: Int): String
}

object SummaryPdfLayout {
    private const val CELL_GAP = 6f
    private const val ROW_PAD = 3f
    private const val RIGHT = PdfPageSpec.WIDTH - PdfPageSpec.MARGIN

    private enum class Align { LEFT, RIGHT }

    private class Column(val header: String, val x: Float, val width: Float, val align: Align = Align.LEFT)

    /** One table row: a cell per column, and an optional line under it that stays on its page (a day total). */
    private class Row(val cells: List<String>, val styles: List<PdfTextStyle>, val trailer: String? = null)

    /** Lays [snapshot] out: the cover, then one section per job in `byJob` order, each page with its footer. */
    fun layout(snapshot: SummarySnapshot, text: SummaryPdfText, measurer: TextMeasurer): List<PdfPage> {
        val builder = Builder(text, measurer)
        builder.cover(snapshot)
        for (job in snapshot.result.byJob) builder.job(snapshot, job)
        return builder.finish(snapshot.range)
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

        fun finish(range: SummaryRange): List<PdfPage> = pages.mapIndexed { index, p ->
            val footer = PdfRun(
                PdfPageSpec.MARGIN, PdfPageSpec.HEIGHT - PdfPageSpec.MARGIN, text.footer(range, index + 1, pages.size), PdfTextStyle.FOOTER,
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

        /** Starts a table: its header now, and again under [continued] on every page it spills onto. */
        private fun startTable(cols: List<Column>, continued: String) {
            columns = cols
            continuationTitle = continued
            ensure(PdfTextStyle.TABLE_HEADER.lineHeight + 2 * ROW_PAD)
            tableHeader()
        }

        private fun row(row: Row) {
            val body = PdfTextStyle.BODY
            val trailerHeight = if (row.trailer != null) PdfTextStyle.BODY_BOLD.lineHeight + ROW_PAD else 0f
            // The most a row can take: a whole page under the actual continuation header. Taller rows are cut to it.
            val room = PdfPageSpec.CONTENT_BOTTOM - PdfPageSpec.MARGIN - continuationHeight() - 2 * ROW_PAD - trailerHeight
            val maxLines = (room / body.lineHeight).toInt().coerceAtLeast(1)
            val wrapped = columns.mapIndexed { i, c ->
                val lines = wrap(row.cells[i], c.width - CELL_GAP, row.styles[i])
                if (lines.size > maxLines) {
                    lines.take(maxLines - 1) + withEllipsis(lines[maxLines - 1], c.width - CELL_GAP, row.styles[i])
                } else {
                    lines
                }
            }
            val lineCount = wrapped.maxOf { it.size }
            val cellsHeight = lineCount * body.lineHeight + 2 * ROW_PAD
            ensure(cellsHeight + trailerHeight)
            val top = y
            columns.forEachIndexed { i, c -> wrapped[i].forEachIndexed { line, s -> cell(c, s, row.styles[i], top, line) } }
            y += cellsHeight
            if (row.trailer != null) {
                val style = PdfTextStyle.BODY_BOLD
                val w = width(row.trailer, style)
                page.runs += PdfRun(RIGHT - w, y + style.size - ROW_PAD, row.trailer, style)
                y += trailerHeight
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
            startTable(
                listOf(
                    Column(text.coverJob, PdfPageSpec.MARGIN, w - 3 * numeric),
                    Column(text.coverBlocks, PdfPageSpec.MARGIN + w - 3 * numeric, numeric, Align.RIGHT),
                    Column(text.coverTotal, PdfPageSpec.MARGIN + w - 2 * numeric, numeric, Align.RIGHT),
                    Column(text.coverBilled, PdfPageSpec.MARGIN + w - numeric, numeric, Align.RIGHT),
                ),
                continued = text.continued(text.title),
            )
            val body = PdfTextStyle.BODY
            for (job in result.byJob) {
                row(
                    Row(
                        listOf(text.jobName(job.name, job.archived), text.blocks(job.blockCount), text.duration(job.totalMs), text.duration(job.billedMs)),
                        listOf(body, body, body, PdfTextStyle.BODY_BOLD),
                    ),
                )
            }
            continuationTitle = null
            y += 14f
            lines(text.totals(result.billedMs, result.onClockMs), PdfTextStyle.HEADING, gapAfter = 6f)
            if (result.hasUnlabelled) lines(text.unlabelled(result.unlabelledOnSiteMs, result.unlabelledTravelMs), PdfTextStyle.BODY)
        }

        fun job(snapshot: SummarySnapshot, job: JobSummary) {
            val perDay = snapshot.result.perDay
            val name = text.jobName(job.name, job.archived)
            newPage()
            continuationTitle = null
            lines(name, PdfTextStyle.HEADING, gapAfter = 2f)
            lines(text.rangeLong(snapshot.range), PdfTextStyle.SUBHEADING)
            lines(text.jobTotals(job.billedMs, job.totalMs, job.billableMs), PdfTextStyle.BODY_BOLD, gapAfter = 10f)

            val fixed = listOf(60f, 98f, 56f, 62f)
            val rest = PdfPageSpec.CONTENT_WIDTH - fixed.sum()
            val xs = fixed.runningFold(PdfPageSpec.MARGIN) { acc, wd -> acc + wd }
            startTable(
                listOf(
                    Column(text.colDate, xs[0], fixed[0]),
                    Column(text.colTime, xs[1], fixed[1]),
                    Column(text.colDuration, xs[2], fixed[2], Align.RIGHT),
                    Column(text.colBilled, xs[3], fixed[3], Align.RIGHT),
                    Column(text.colPlaces, xs[4], rest / 2),
                    Column(text.colNotes, xs[4] + rest / 2, rest / 2),
                ),
                continued = text.continued(name),
            )
            job.pieces.forEachIndexed { i, piece ->
                val lastOfDay = job.pieces.getOrNull(i + 1)?.date != piece.date
                val trailer = if (perDay && lastOfDay) job.days.firstOrNull { it.date == piece.date }?.let { text.dayTotal(it.billedMs) } else null
                row(Row(pieceCells(snapshot, piece, perDay), List(6) { PdfTextStyle.BODY }, trailer))
            }
        }

        private fun pieceCells(snapshot: SummarySnapshot, p: BlockPiece, perDay: Boolean): List<String> {
            val billed = when {
                perDay -> ""
                !p.billable -> text.notBillable()
                else -> text.duration(p.billedMs ?: 0L)
            }
            return listOf(
                text.date(p.date),
                text.timeRange(p.start, p.end, snapshot.zone),
                text.duration(p.durationMs),
                billed,
                coveredPlaces(p, snapshot.visits, snapshot.places, text.unnamedPlace).joinToString(", "),
                p.notes.orEmpty(),
            )
        }
    }

    private const val CONTINUATION_GAP = 6f
    private const val MAX_TITLE_LINES = 3
}
