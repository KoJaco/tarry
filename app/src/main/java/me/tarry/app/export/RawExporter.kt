package me.tarry.app.export

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.repository.TrackingRepository
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat(val mimeType: String, val extension: String) {
    GPX("application/gpx+xml", "gpx"),
    CSV("text/csv", "csv"),
}

/** What the user asked to export. Raw pings only leave the device through one of these (AGENTS rule 2). */
sealed interface ExportRequest {
    val format: ExportFormat

    data class Session(val sessionId: Long, override val format: ExportFormat) : ExportRequest

    /** Inclusive local dates, interpreted in [zone]. */
    data class Range(
        val from: LocalDate,
        val to: LocalDate,
        val zone: ZoneId,
        override val format: ExportFormat,
    ) : ExportRequest
}

/** File name offered to the system file picker. */
fun ExportRequest.suggestedFileName(): String {
    val stem = when (this) {
        is ExportRequest.Session -> "tarry-session-$sessionId"
        is ExportRequest.Range -> "tarry-pings-${from}_to_$to"
    }
    return "$stem.${format.extension}"
}

private fun ExportRequest.trackName(): String = when (this) {
    is ExportRequest.Session -> "Tarry session $sessionId"
    is ExportRequest.Range -> "Tarry $from to $to"
}

/**
 * Writes the raw pings for an [ExportRequest] to a user-chosen document (Storage Access Framework `Uri`).
 * Only called from a user action; there is no scheduled or background export.
 */
@Singleton
class RawExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val trackingRepo: TrackingRepository,
) {
    /** Returns the number of pings written. Throws [IOException] if the document cannot be opened. */
    suspend fun export(request: ExportRequest, uri: Uri): Int = withContext(Dispatchers.IO) {
        val pings = load(request)
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open $uri for writing")
        // Formatters append straight into the buffered writer, so no whole-file string is built.
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            when (request.format) {
                ExportFormat.CSV -> PingCsvFormat.write(pings, writer)
                ExportFormat.GPX -> PingGpxFormat.write(pings, request.trackName(), writer)
            }
        }
        pings.size
    }

    private suspend fun load(request: ExportRequest): List<LocationPing> = when (request) {
        is ExportRequest.Session -> trackingRepo.pingsForSession(request.sessionId)
        is ExportRequest.Range -> trackingRepo.pingsInRange(
            request.from.atStartOfDay(request.zone).toInstant().toEpochMilli(),
            request.to.plusDays(1).atStartOfDay(request.zone).toInstant().toEpochMilli(),
        )
    }
}
