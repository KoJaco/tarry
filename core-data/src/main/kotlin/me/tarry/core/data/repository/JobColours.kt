package me.tarry.core.data.repository

import me.tarry.core.data.db.entity.Job

/**
 * The colour a job takes from [paletteArgb] (the app's `JobPalette` keys; core-data cannot see the UI theme).
 * Only jobs that are neither deleted nor done count: a done job has given its colour back. The first palette colour no
 * such job uses wins; when every colour is in use, the least-used one does (ties go to palette order).
 */
internal fun pickJobColour(jobs: List<Job>, paletteArgb: List<Int>): Int {
    val inUse = jobs.filter { !it.deleted && !it.archived }.groupingBy { it.colorArgb }.eachCount()
    return paletteArgb.firstOrNull { it !in inUse }
        ?: paletteArgb.minByOrNull { inUse[it] ?: 0 }
        ?: 0
}
