package me.tarry.app.export

import me.tarry.core.data.db.entity.LocationPing
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Raw location ping CSV, the fixture format phase 2b reads. One header row, then one row per ping, in the
 * order given (the exporters pass pings ordered by timestamp).
 *
 * Columns, exactly and in this order:
 * `id,timestamp_utc,zone_id,lat,lng,accuracy_m,speed_mps,bearing,activity,source,session_id`
 *
 * | column | content |
 * |---|---|
 * | `id` | ping row id (Long) |
 * | `timestamp_utc` | ISO-8601 UTC with millisecond precision, `yyyy-MM-ddTHH:mm:ss.SSSZ`, e.g. `2026-03-02T21:15:07.250Z`. `Instant.parse` reads it back. |
 * | `zone_id` | IANA id of the time zone at capture, e.g. `Australia/Sydney` |
 * | `lat`, `lng` | decimal degrees, plain notation without exponent (never `1.0E-5`) or trailing zeros (`90.0` is `90`) |
 * | `accuracy_m` | horizontal accuracy in metres |
 * | `speed_mps` | metres per second; empty if unknown |
 * | `bearing` | degrees; empty if unknown |
 * | `activity` | `STILL`, `WALKING`, `IN_VEHICLE`, `ON_BICYCLE`, `UNKNOWN`; empty if none recorded yet |
 * | `source` | `FUSED`, `GEOFENCE_EXIT` or `MANUAL_START` |
 * | `session_id` | owning tracking session id (Long) |
 *
 * Null values and non-finite numbers are empty cells. Quoting follows RFC 4180: a cell containing a comma, a
 * double quote, CR or LF is wrapped in double quotes with inner quotes doubled. Lines end with CRLF. The file is
 * UTF-8 without a byte-order mark.
 */
object PingCsvFormat {
    val COLUMNS: List<String> = listOf(
        "id", "timestamp_utc", "zone_id", "lat", "lng", "accuracy_m", "speed_mps", "bearing", "activity", "source",
        "session_id",
    )

    const val HEADER: String = "id,timestamp_utc,zone_id,lat,lng,accuracy_m,speed_mps,bearing,activity,source,session_id"

    private const val EOL = "\r\n"

    /** Streams the header and one line per ping to [out]; never builds the whole document in memory. */
    fun write(pings: Iterable<LocationPing>, out: Appendable) {
        out.append(HEADER).append(EOL)
        for (ping in pings) out.append(line(ping)).append(EOL)
    }

    /** One data row without the line terminator. */
    fun line(ping: LocationPing): String = listOf(
        ping.id.toString(),
        RawFormat.isoUtc(ping.timestamp),
        ping.zoneId,
        RawFormat.plain(ping.lat),
        RawFormat.plain(ping.lng),
        RawFormat.plain(ping.accuracyM),
        ping.speedMps?.let(RawFormat::plain).orEmpty(),
        ping.bearing?.let(RawFormat::plain).orEmpty(),
        ping.activity?.name.orEmpty(),
        ping.source.name,
        ping.sessionId.toString(),
    ).joinToString(",") { quote(it) }

    /** RFC 4180 quoting. */
    fun quote(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else {
            cell
        }
}

/**
 * GPX 1.1 export. One `<trk>`, with one `<trkseg>` per tracking session (a new segment starts whenever the
 * session id changes in the input order, which the exporters sort by timestamp). Each ping is a `<trkpt>` with
 * `lat`, `lon` and `<time>` (UTC, millisecond precision).
 *
 * GPX 1.1 has no field for accuracy, speed or bearing, so they go in an `<extensions>` element using the
 * [NAMESPACE] namespace (prefix `tarry`): `accuracy` (metres), and when known `speed` (m/s), `bearing`
 * (degrees), `activity` and `source`. Pings are written exactly as stored; nothing is smoothed or dropped.
 */
object PingGpxFormat {
    const val NAMESPACE = "urn:tarry:gpx:1"
    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"

    /** Streams the document to [out]. [trackName] is XML-escaped. */
    fun write(pings: Iterable<LocationPing>, trackName: String, out: Appendable) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"Tarry\" xmlns=\"$GPX_NS\" xmlns:tarry=\"$NAMESPACE\">\n")
        out.append("  <trk>\n")
        out.append("    <name>").append(escape(trackName)).append("</name>\n")
        var currentSession: Long? = null
        for (ping in pings) {
            if (currentSession != ping.sessionId) {
                if (currentSession != null) out.append("    </trkseg>\n")
                out.append("    <trkseg>\n")
                currentSession = ping.sessionId
            }
            point(ping, out)
        }
        if (currentSession != null) out.append("    </trkseg>\n")
        out.append("  </trk>\n")
        out.append("</gpx>\n")
    }

    private fun point(p: LocationPing, out: Appendable) {
        out.append("      <trkpt lat=\"").append(RawFormat.plain(p.lat))
            .append("\" lon=\"").append(RawFormat.plain(p.lng)).append("\">\n")
        out.append("        <time>").append(RawFormat.isoUtc(p.timestamp)).append("</time>\n")
        out.append("        <extensions>\n")
        out.append("          <tarry:accuracy>").append(RawFormat.plain(p.accuracyM)).append("</tarry:accuracy>\n")
        p.speedMps?.let { out.append("          <tarry:speed>").append(RawFormat.plain(it)).append("</tarry:speed>\n") }
        p.bearing?.let { out.append("          <tarry:bearing>").append(RawFormat.plain(it)).append("</tarry:bearing>\n") }
        p.activity?.let { out.append("          <tarry:activity>").append(it.name).append("</tarry:activity>\n") }
        out.append("          <tarry:source>").append(p.source.name).append("</tarry:source>\n")
        out.append("        </extensions>\n")
        out.append("      </trkpt>\n")
    }

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

internal object RawFormat {
    private val ISO_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun isoUtc(epochMs: Long): String = ISO_UTC.format(Instant.ofEpochMilli(epochMs))

    /** Plain decimal text without exponent or trailing zeros (`90.0` is `90`); empty for NaN and infinities. */
    fun plain(value: Double): String =
        if (value.isFinite()) BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else ""

    /** Goes through the float's own shortest text so 12.3f stays `12.3`, not `12.300000190734863`. */
    fun plain(value: Float): String =
        if (value.isFinite()) BigDecimal(value.toString()).stripTrailingZeros().toPlainString() else ""
}
