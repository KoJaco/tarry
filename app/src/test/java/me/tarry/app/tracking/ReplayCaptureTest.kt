package me.tarry.app.tracking

import me.tarry.core.data.model.ActivityType
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Opt-in capture-side replay of real dogfood CSV exports, output only (never asserts). Skipped unless the system
 * property `tarry.replay` names a directory
 * (`gradlew :app:testDebugUnitTest --tests *ReplayCaptureTest -Ptarry.replay=<dir> -i`).
 *
 * Caveat: an export holds only pings that were already saved, so the filter sees pre-filtered data (drop counts are a
 * lower bound) and the machine gets the saved ping's activity column as an activity change when it changes. No
 * geofence events exist in an export, so STATIONARY exits come from the machine's own safety net.
 */
class ReplayCaptureTest {
    private data class Row(val fix: Fix, val activity: ActivityType?, val session: Long?)

    @Test
    fun replayCapture() {
        val dir = System.getProperty("tarry.replay")
        assumeTrue("tarry.replay not set", !dir.isNullOrBlank())
        val files = File(dir!!).listFiles { f -> f.extension.equals("csv", true) }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no CSV files in $dir", files.isNotEmpty())
        for (file in files) replay(file)
    }

    private fun replay(file: File) {
        val rows = parse(file.readText())
        val config = TrackingConfig()
        val timeIn = mutableMapOf(CaptureMode.STATIONARY to 0L, CaptureMode.MOVING to 0L)
        val decisions = sortedMapOf<FilterDecision, Int>()
        var switches = 0
        val switchLog = mutableListOf<String>()
        for ((session, sessionRows) in rows.groupBy { it.session }.toSortedMap(compareBy { it ?: -1L })) {
            val filter = PingFilter(config)
            val machine = CaptureModeMachine(config)
            machine.start()
            var lastActivity: ActivityType? = null
            var prev: Fix? = null
            for (row in sessionRows.sortedBy { it.fix.timeMs }) {
                val before = machine.mode
                if (prev != null) timeIn.merge(before, row.fix.timeMs - prev.timeMs, Long::plus)
                if (row.activity != null && row.activity != lastActivity) {
                    lastActivity = row.activity
                    machine.onActivity(row.activity)
                }
                val modeNow = machine.mode
                val decision = filter.offer(row.fix, modeNow)
                decisions.merge(decision, 1, Int::plus)
                if (decision.accepted || decision == FilterDecision.DROP_DISPLACEMENT ||
                    decision == FilterDecision.DROP_JITTER
                ) {
                    machine.onAcceptedFix(row.fix)
                }
                if (machine.mode != before) {
                    switches++
                    switchLog += "  ${Instant.ofEpochMilli(row.fix.timeMs)} session=$session $before -> ${machine.mode}"
                }
                prev = row.fix
            }
        }
        println("== CAPTURE ${file.name}: ${rows.size} fixes")
        timeIn.forEach { (m, ms) -> println("  time in $m: ${ms / 60_000} min") }
        println("  mode switches: $switches")
        switchLog.forEach(::println)
        val kept = decisions[FilterDecision.ACCEPT] ?: 0
        println("  kept (ACCEPT): $kept; dropped: ${decisions.filterKeys { it != FilterDecision.ACCEPT }.values.sum()}")
        decisions.forEach { (d, n) -> println("    $d: $n") }
    }

    private fun parse(text: String): List<Row> =
        text.removePrefix("﻿").lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(",")
            Row(
                Fix(
                    lat = c[3].toDouble(),
                    lng = c[4].toDouble(),
                    accuracyM = c[5].toFloat(),
                    timeMs = Instant.parse(c[1]).toEpochMilli(),
                    speedMps = c[6].toFloatOrNull(),
                    bearing = c[7].toFloatOrNull(),
                ),
                c[8].takeIf { it.isNotEmpty() }?.let { n -> ActivityType.entries.firstOrNull { it.name == n } },
                c[10].toLongOrNull(),
            )
        }.toList()
}
