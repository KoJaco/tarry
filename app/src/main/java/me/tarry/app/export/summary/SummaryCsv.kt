package me.tarry.app.export.summary

import me.tarry.app.export.PingCsvFormat
import me.tarry.app.summary.BlockPiece
import me.tarry.app.summary.JobSummary
import me.tarry.app.summary.SummaryResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Summary as CSV (phase 7 step 4): one row per block piece, and in per-day billing mode one `day_total` row per job
 * per day. Blocks only: no visits, trips, coordinates or place names.
 *
 * RFC 4180 like [PingCsvFormat]: UTF-8 without a BOM, CRLF line ends, a field quoted only when it holds a comma, quote, CR
 * or LF. Locale-independent: ASCII digits, `true`/`false`, no thousands separators.
 *
 * Columns (frozen, see [HEADER]): `row_type, job_block_id, job_id, job_name, date, start, end, duration_min, billable,
 * billed_min, clipped, notes`. `duration_min` and `billed_min` are whole minutes. A block split at local midnight gives
 * two rows with the same `job_block_id`; `clipped` is true only where the range edge cut the piece. Rows are ordered by
 * date, start, job name; a `day_total` row follows its job's last block row of that day.
 */
object SummaryCsv {
    const val HEADER: String =
        "row_type,job_block_id,job_id,job_name,date,start,end,duration_min,billable,billed_min,clipped,notes"

    private const val EOL = "\r\n"
    private const val MINUTE_MS = 60_000L
    private const val BLOCK = "block"
    private const val DAY_TOTAL = "day_total"
    private val FORMULA_STARTS = setOf('=', '+', '-', '@')

    // Locale.ROOT: ASCII digits whatever the phone's language. The offset is the zone's at that instant.
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", Locale.ROOT)

    /** Streams the header and the rows to [out]. */
    fun write(result: SummaryResult, zone: ZoneId, out: Appendable) {
        out.append(HEADER).append(EOL)
        for (row in rows(result, zone)) out.append(row).append(EOL)
    }

    /** The data rows without line terminators. */
    fun rows(result: SummaryResult, zone: ZoneId): List<String> {
        class Entry(val job: JobSummary, val piece: BlockPiece)
        val entries = result.byJob.flatMap { job -> job.pieces.map { Entry(job, it) } }
            .sortedWith(
                compareBy<Entry>({ it.piece.date }, { it.piece.start }, { it.job.name.lowercase(Locale.ROOT) }, { it.job.name })
                    .thenBy { it.job.jobId }.thenBy { it.piece.blockId },
            )
        // A job's day total goes after its last row of that day, even when another job's rows are interleaved.
        val lastIndex = HashMap<Pair<String, LocalDate>, Int>()
        entries.forEachIndexed { index, e -> lastIndex[e.job.jobId to e.piece.date] = index }
        val out = ArrayList<String>(entries.size)
        entries.forEachIndexed { index, e ->
            out += blockRow(e.job, e.piece, zone, perDay = result.perDay)
            if (result.perDay) {
                if (lastIndex[e.job.jobId to e.piece.date] == index) e.job.days.firstOrNull { it.date == e.piece.date }?.let { day ->
                    out += cells(
                        DAY_TOTAL, "", e.job.jobId, e.job.name, day.date.toString(), "", "",
                        (day.billableMs / MINUTE_MS).toString(), "", (day.billedMs / MINUTE_MS).toString(), "", "",
                    )
                }
            }
        }
        return out
    }

    private fun blockRow(job: JobSummary, p: BlockPiece, zone: ZoneId, perDay: Boolean): String = cells(
        BLOCK, p.blockId, job.jobId, job.name, p.date.toString(), time(p.start, zone), time(p.end, zone),
        (p.durationMs / MINUTE_MS).toString(), p.billable.toString(),
        if (perDay) "" else ((p.billedMs ?: 0L) / MINUTE_MS).toString(),
        p.clipped.toString(), guard(p.notes.orEmpty()),
    )

    /** Spreadsheet formula guard (spec 4): a note starting with `=`, `+`, `-` or `@` gets a leading `'`. Notes only. */
    private fun guard(note: String): String = if (note.firstOrNull() in FORMULA_STARTS) "'$note" else note

    private fun time(epochMs: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private fun cells(vararg cell: String): String = cell.joinToString(",") { PingCsvFormat.quote(it) }
}
