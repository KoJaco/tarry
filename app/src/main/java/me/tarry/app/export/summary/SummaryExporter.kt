package me.tarry.app.export.summary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.SummaryRange
import me.tarry.app.summary.SummaryResult
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** What the Summary can be exported as. */
enum class SummaryExportFormat(val mimeType: String, val extension: String) {
    CSV("text/csv", "csv"),
}

/**
 * The Summary as it was when the user asked to export it: one range, one result, one zone. The PDF also needs the billing
 * [rules] (to say them in words) and the [visits] and [places] behind its "places visited" column; the CSV ignores them.
 */
data class SummarySnapshot(
    val range: SummaryRange,
    val zone: ZoneId,
    val result: SummaryResult,
    val rules: BillingRules = BillingRules(),
    /** Visits overlapping the range; deleted ones are filtered where they are used. */
    val visits: List<Visit> = emptyList(),
    /** All places, ignored ones included (visits there are left out of "places visited"). */
    val places: List<Place> = emptyList(),
)

/** File name offered to the system file picker: `tarry-summary-<from>_<to>.csv`, ISO dates, both always present. */
fun SummaryExportFormat.suggestedFileName(range: SummaryRange): String = "tarry-summary-${range.from}_${range.to}.$extension"

enum class SummaryExportError { CANNOT_OPEN, WRITE_FAILED }

class SummaryExportException(val error: SummaryExportError, cause: Throwable? = null) : Exception(error.name, cause)

/**
 * Writes the Summary to a user-chosen document (Storage Access Framework `Uri`). Only called from the export button;
 * nothing runs on its own.
 */
@Singleton
class SummaryExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Writes [snapshot] as [format] to [uri] and returns the number of blocks in it. Throws [SummaryExportException]:
     * CANNOT_OPEN if the document cannot be opened, WRITE_FAILED if writing fails (the partly written document is then
     * deleted, best effort).
     */
    suspend fun export(format: SummaryExportFormat, snapshot: SummarySnapshot, uri: Uri): Int = export(
        format, snapshot,
        open = { context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing") },
        discard = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
    )

    suspend fun export(
        format: SummaryExportFormat,
        snapshot: SummarySnapshot,
        open: () -> OutputStream,
        discard: () -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val out = try {
            open()
        } catch (e: IOException) {
            throw SummaryExportException(SummaryExportError.CANNOT_OPEN, e)
        } catch (e: SecurityException) {
            throw SummaryExportException(SummaryExportError.CANNOT_OPEN, e)
        }
        try {
            out.bufferedWriter(Charsets.UTF_8).use { writer ->
                when (format) {
                    SummaryExportFormat.CSV -> SummaryCsv.write(snapshot.result, snapshot.zone, writer)
                }
            }
        } catch (e: Exception) {
            runCatching { discard() }
            if (e is CancellationException) throw e
            throw SummaryExportException(SummaryExportError.WRITE_FAILED, e)
        }
        snapshot.result.byJob.sumOf { it.blockCount }
    }
}
