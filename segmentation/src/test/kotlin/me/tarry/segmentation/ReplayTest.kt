package me.tarry.segmentation

import me.tarry.segmentation.trace.CsvTraceLoader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Opt-in replay of real dogfood traces, output only (never asserts). Skipped unless the system property
 * `tarry.replay` names a directory of ping CSV exports (`gradlew :segmentation:test -Ptarry.replay=<dir> -i`).
 * Real traces must never be copied into the repo.
 */
class ReplayTest {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Test
    fun replayRealTraces() {
        val dir = System.getProperty("tarry.replay")
        assumeTrue("tarry.replay not set", !dir.isNullOrBlank())
        val files = File(dir!!).listFiles { f -> f.extension.equals("csv", true) }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no CSV files in $dir", files.isNotEmpty())
        for (file in files) replay(file)
    }

    private fun replay(file: File) {
        val text = file.readText()
        val pings = CsvTraceLoader.parse(text).sortedBy { it.timeMs }
        if (pings.isEmpty()) {
            println("== ${file.name}: no pings")
            return
        }
        val zone = zoneOf(text)
        val sessions = pings.filter { it.sessionId != null }.groupBy { it.sessionId }.values
            .map { TimeRange(it.first().timeMs, it.last().timeMs) }
        val range = TimeRange(pings.first().timeMs, pings.last().timeMs + 1)
        val result = Segmenter.segment(
            SegmentationInput(range = range, pings = pings, sessions = sessions),
            SegmentationParams(),
        )

        println("== ${file.name} (zone $zone, ${pings.size} pings, ${sessions.size} sessions)")
        val rows = buildList {
            result.stays.forEach { add(it.range.startMs to stayLine(it, pings, zone)) }
            result.trips.forEach { add(it.range.startMs to tripLine(it, pings, zone)) }
            result.gaps.forEach { add(it.range.startMs to gapLine(it, pings, zone)) }
        }
        rows.sortedBy { it.first }.forEach { println(it.second) }
        println(
            "SUMMARY ${file.name}: stays=${result.stays.size} trips=${result.trips.size} " +
                "gaps=${result.gaps.size} newPlaces=${result.newPlaces.size}",
        )
    }

    private fun zoneOf(text: String): ZoneId {
        val id = text.removePrefix("﻿").lineSequence().drop(1).firstOrNull { it.isNotBlank() }
            ?.split(",")?.getOrNull(2)
        return id?.takeIf { it.isNotBlank() }?.let { ZoneId.of(it) } ?: ZoneId.of("UTC")
    }

    private fun time(ms: Long, zone: ZoneId) = fmt.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone))
    private fun timeOnly(ms: Long, zone: ZoneId) = timeFmt.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone))

    private fun span(r: TimeRange, zone: ZoneId) =
        "${time(r.startMs, zone)} - ${timeOnly(r.endMs, zone)}  ${dur(r.durationMs)}"

    private fun dur(ms: Long): String {
        val s = ms / 1000
        return "%dh%02dm%02ds".format(s / 3600, s % 3600 / 60, s % 60)
    }

    private fun within(r: TimeRange, pings: List<Ping>) = pings.filter { it.timeMs in r }

    private fun meanAcc(ps: List<Ping>) =
        if (ps.isEmpty()) "n/a" else String.format(Locale.ROOT, "%.1f", ps.map { it.accuracyM.toDouble() }.average())

    private fun stayLine(s: Stay, pings: List<Ping>, zone: ZoneId): String {
        val ps = within(s.range, pings)
        val label = when (val p = s.place) {
            is PlaceRef.New -> "P${p.index + 1}"
            is PlaceRef.Existing -> "existing:${p.id}"
        }
        return String.format(
            Locale.ROOT,
            "STAY  %s  centroid=(%.6f, %.6f) place=%s pings=%d meanAcc=%sm conf=%.2f%s%s",
            span(s.range, zone), s.lat, s.lng, label, ps.size, meanAcc(ps), s.confidence,
            if (s.bridged) " bridged" else "", if (s.mayBeOngoing) " ongoing" else "",
        )
    }

    private fun tripLine(t: Trip, pings: List<Ping>, zone: ZoneId): String {
        val ps = within(t.range, pings)
        return String.format(
            Locale.ROOT,
            "TRIP  %s  dist=%.0fm mode=%s pings=%d meanAcc=%sm from=%s to=%s",
            span(t.range, zone), t.distanceM, t.mode, ps.size, meanAcc(ps), t.fromStay ?: "-", t.toStay ?: "-",
        )
    }

    private fun gapLine(g: Gap, pings: List<Ping>, zone: ZoneId) =
        "GAP   ${span(g.range, zone)}  cause=${g.cause} pings=${within(g.range, pings).size}"
}
