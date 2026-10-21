package me.tarry.segmentation.trace

import me.tarry.segmentation.Activity
import me.tarry.segmentation.Ping
import java.time.Instant

/** Reads the phase 2a raw ping CSV export from the classpath into [Ping]s. Speed, bearing and source are ignored. */
object CsvTraceLoader {
    const val HEADER = "id,timestamp_utc,zone_id,lat,lng,accuracy_m,speed_mps,bearing,activity,source,session_id"

    fun load(resourcePath: String): List<Ping> {
        val stream = CsvTraceLoader::class.java.getResourceAsStream(resourcePath)
            ?: error("Trace resource not found on classpath: $resourcePath")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return parse(text)
    }

    fun parse(text: String): List<Ping> {
        val lines = text.removePrefix("﻿").split("\r\n", "\n").filter { it.isNotBlank() }
        require(lines.isNotEmpty() && lines[0].trim() == HEADER) {
            "Unexpected CSV header: '${lines.firstOrNull()}', expected '$HEADER'"
        }
        return lines.drop(1).mapIndexed { index, line ->
            val cells = splitRow(line)
            require(cells.size == 11) { "Row ${index + 2}: expected 11 columns, got ${cells.size}" }
            Ping(
                timeMs = Instant.parse(cells[1]).toEpochMilli(),
                lat = cells[3].toDouble(),
                lng = cells[4].toDouble(),
                accuracyM = cells[5].toFloat(),
                activity = cells[8].takeIf { it.isNotEmpty() }?.let { name ->
                    Activity.entries.firstOrNull { it.name == name } ?: Activity.UNKNOWN
                },
                sessionId = cells[10].takeIf { it.isNotEmpty() }?.toLong(),
            )
        }
    }

    /** RFC 4180 single-line split (quoted cells with doubled quotes). */
    private fun splitRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        cells += sb.toString()
        return cells
    }
}
