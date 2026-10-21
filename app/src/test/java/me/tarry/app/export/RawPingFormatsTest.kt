package me.tarry.app.export

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.PingSource
import org.junit.Test
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class RawPingFormatsTest {
    // 2026-03-02T21:15:07.250Z
    private val t0 = Instant.parse("2026-03-02T21:15:07.250Z").toEpochMilli()

    private fun ping(
        id: Long = 1,
        ts: Long = t0,
        zone: String = "Australia/Sydney",
        lat: Double = -33.8688,
        lng: Double = 151.2093,
        accuracy: Float = 12.5f,
        speed: Float? = 1.5f,
        bearing: Float? = 90f,
        activity: ActivityType? = ActivityType.WALKING,
        source: PingSource = PingSource.FUSED,
        session: Long = 7,
    ) = LocationPing(
        id = id, timestamp = ts, zoneId = zone, lat = lat, lng = lng, accuracyM = accuracy, speedMps = speed,
        bearing = bearing, activity = activity, source = source, sessionId = session,
    )

    private fun csv(vararg pings: LocationPing) = StringBuilder().also { PingCsvFormat.write(pings.toList(), it) }.toString()

    private fun gpx(name: String = "Test", vararg pings: LocationPing) =
        StringBuilder().also { PingGpxFormat.write(pings.toList(), name, it) }.toString()

    @Test
    fun csv_headerHasExactlyTheAgreedColumns() {
        assertThat(PingCsvFormat.HEADER)
            .isEqualTo("id,timestamp_utc,zone_id,lat,lng,accuracy_m,speed_mps,bearing,activity,source,session_id")
        assertThat(PingCsvFormat.COLUMNS.joinToString(",")).isEqualTo(PingCsvFormat.HEADER)
        assertThat(csv().trimEnd()).isEqualTo(PingCsvFormat.HEADER)
    }

    @Test
    fun csv_rowFormatsAllFields() {
        val lines = csv(ping()).split("\r\n")
        assertThat(lines[1])
            .isEqualTo("1,2026-03-02T21:15:07.250Z,Australia/Sydney,-33.8688,151.2093,12.5,1.5,90,WALKING,FUSED,7")
        assertThat(lines[2]).isEmpty()
    }

    @Test
    fun csv_nullsAreEmptyCells() {
        val row = csv(ping(speed = null, bearing = null, activity = null)).split("\r\n")[1]
        assertThat(row).isEqualTo("1,2026-03-02T21:15:07.250Z,Australia/Sydney,-33.8688,151.2093,12.5,,,,FUSED,7")
        assertThat(row.split(",")).hasSize(PingCsvFormat.COLUMNS.size)
    }

    @Test
    fun csv_timestampIsUtcWithMillisAndRoundTrips() {
        val row = csv(ping(ts = 1_000L)).split("\r\n")[1].split(",")
        assertThat(row[1]).isEqualTo("1970-01-01T00:00:01.000Z")
        assertThat(Instant.parse(row[1]).toEpochMilli()).isEqualTo(1_000L)
    }

    @Test
    fun csv_numbersNeverUseExponentNotation() {
        val row = csv(ping(lat = 0.00001, lng = -0.0000025, accuracy = 0.0001f)).split("\r\n")[1].split(",")
        assertThat(row[3]).isEqualTo("0.00001")
        assertThat(row[4]).isEqualTo("-0.0000025")
        assertThat(row[5]).isEqualTo("0.0001")
    }

    @Test
    fun csv_floatsKeepTheirShortestText() {
        val row = csv(ping(accuracy = 12.3f, speed = 0.1f)).split("\r\n")[1].split(",")
        assertThat(row[5]).isEqualTo("12.3")
        assertThat(row[6]).isEqualTo("0.1")
    }

    @Test
    fun csv_nonFiniteNumbersAreEmpty() {
        val row = csv(ping(speed = Float.NaN, bearing = Float.POSITIVE_INFINITY)).split("\r\n")[1].split(",")
        assertThat(row[6]).isEmpty()
        assertThat(row[7]).isEmpty()
    }

    @Test
    fun csv_quotingFollowsRfc4180() {
        assertThat(PingCsvFormat.quote("plain")).isEqualTo("plain")
        assertThat(PingCsvFormat.quote("a,b")).isEqualTo("\"a,b\"")
        assertThat(PingCsvFormat.quote("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(PingCsvFormat.quote("line\nbreak")).isEqualTo("\"line\nbreak\"")
        assertThat(PingCsvFormat.quote("cr\rhere")).isEqualTo("\"cr\rhere\"")
        assertThat(PingCsvFormat.quote("")).isEmpty()
        assertThat(csv(ping(zone = "Odd,Zone")).split("\r\n")[1]).contains(",\"Odd,Zone\",")
    }

    @Test
    fun csv_writesEveryPingInOrderWithCrlf() {
        val text = csv(ping(id = 1), ping(id = 2), ping(id = 3))
        assertThat(text.split("\r\n").filter { it.isNotEmpty() }.map { it.substringBefore(',') })
            .containsExactly("id", "1", "2", "3").inOrder()
        assertThat(text.replace("\r\n", "")).doesNotContain("\n")
    }

    @Test
    fun csv_streamsToAnyAppendable() {
        // A writer that discards output proves nothing is accumulated by the formatter itself.
        var chars = 0L
        val sink = object : Appendable {
            override fun append(csq: CharSequence?): Appendable = also { chars += csq?.length ?: 0 }
            override fun append(csq: CharSequence?, start: Int, end: Int): Appendable = also { chars += end - start }
            override fun append(c: Char): Appendable = also { chars++ }
        }
        PingCsvFormat.write((1L..1_000L).asSequence().map { ping(id = it) }.asIterable(), sink)
        assertThat(chars).isGreaterThan(1_000L * 50)
    }

    @Test
    fun gpx_isWellFormedGpx11WithTimeAndAccuracyExtension() {
        val text = gpx("Day", ping(), ping(id = 2, ts = t0 + 20_000, lat = -33.87, lng = 151.21))
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(text.byteInputStream())

        val root = doc.documentElement
        assertThat(root.localName).isEqualTo("gpx")
        assertThat(root.getAttribute("version")).isEqualTo("1.1")
        assertThat(root.namespaceURI).isEqualTo("http://www.topografix.com/GPX/1/1")
        val points = doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertThat(points.length).isEqualTo(2)
        val first = points.item(0) as org.w3c.dom.Element
        assertThat(first.getAttribute("lat")).isEqualTo("-33.8688")
        assertThat(first.getAttribute("lon")).isEqualTo("151.2093")
        assertThat(first.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "time").item(0).textContent)
            .isEqualTo("2026-03-02T21:15:07.250Z")
        val accuracy = first.getElementsByTagNameNS(PingGpxFormat.NAMESPACE, "accuracy").item(0)
        assertThat(accuracy.textContent).isEqualTo("12.5")
        assertThat(accuracy.parentNode.localName).isEqualTo("extensions")
    }

    @Test
    fun gpx_omitsNullOptionalExtensions() {
        val text = gpx("x", ping(speed = null, bearing = null, activity = null))
        assertThat(text).contains("<tarry:accuracy>12.5</tarry:accuracy>")
        assertThat(text).contains("<tarry:source>FUSED</tarry:source>")
        assertThat(text).doesNotContain("tarry:speed")
        assertThat(text).doesNotContain("tarry:bearing")
        assertThat(text).doesNotContain("tarry:activity")
    }

    @Test
    fun gpx_startsNewSegmentWhenSessionChanges() {
        val text = gpx("x", ping(id = 1, session = 1), ping(id = 2, session = 1), ping(id = 3, session = 2))
        assertThat(Regex("<trkseg>").findAll(text).count()).isEqualTo(2)
        assertThat(Regex("</trkseg>").findAll(text).count()).isEqualTo(2)
        assertThat(Regex("<trkpt ").findAll(text).count()).isEqualTo(3)
    }

    @Test
    fun gpx_emptyInputIsStillValidXml() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx("none").byteInputStream())
        assertThat(doc.getElementsByTagName("trkpt").length).isEqualTo(0)
    }

    @Test
    fun gpx_escapesTheTrackName() {
        val text = gpx("A & B <\"c\">")
        assertThat(text).contains("<name>A &amp; B &lt;&quot;c&quot;&gt;</name>")
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
    }
}
