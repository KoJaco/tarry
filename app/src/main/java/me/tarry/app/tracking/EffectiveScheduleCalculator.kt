package me.tarry.app.tracking

import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/**
 * Everything the calculator needs; plain data, so it is trivial to fake.
 *
 * @property weekOverrides week overrides keyed by the ISO date of their Monday. A week override replaces the template.
 * @property manualOverrides rows from `manual_override`. Rows that are inactive and have no `end` are ignored.
 * @property testWindowStartMs debug-only test window start; its length is [TrackingConfig.debugTestWindowLengthMs].
 * @property edits the schedule save history, ascending by time; see [scheduleAt].
 */
data class ScheduleInputs(
    val template: Schedule,
    val weekOverrides: Map<LocalDate, Schedule> = emptyMap(),
    val manualOverrides: List<ManualOverride> = emptyList(),
    val testWindowStartMs: Long? = null,
    val edits: List<ScheduleEdit> = emptyList(),
) {
    /**
     * The schedule that was in force at [t] for the week starting [weekStart]: an edit counts from the moment it was
     * saved, so the edits after [t] are undone. Template: the earliest edit after [t] gives its `templateBefore`, else
     * the current template. Override: the earliest edit after [t] that touched this week gives its `overrideBefore`
     * (null = none), else the current override. A week override replaces the template.
     */
    fun scheduleAt(weekStart: LocalDate, t: Instant): Schedule {
        val ms = t.toEpochMilli()
        val templateAt = edits.firstOrNull { it.atMs > ms }?.templateBefore ?: template
        val touched = edits.firstOrNull { it.atMs > ms && it.weekBefore?.weekStart == weekStart }
        val overrideAt = if (touched != null) touched.weekBefore?.overrideBefore else weekOverrides[weekStart]
        return overrideAt ?: templateAt
    }
}

/** A scheduled window, [start] inclusive and [end] exclusive. */
data class WindowBounds(val start: Instant, val end: Instant)

/**
 * @property isActive whether capture should be running at the instant.
 * @property nextTransition next instant (within the look-ahead) at which [isActive] changes, or null.
 * @property currentWindow the scheduled window (template, week override or test window) containing the instant,
 *   regardless of manual layering; null outside scheduled windows.
 * @property winningOverride the manual override in force (latest start wins), or null.
 * @property winningOverrideEnd when [winningOverride] ends, or null if open-ended.
 */
data class EffectiveSchedule(
    val isActive: Boolean,
    val nextTransition: Instant?,
    val currentWindow: WindowBounds?,
    val winningOverride: ManualOverride? = null,
    val winningOverrideEnd: Instant? = null,
) {
    /** True when capture is on only because of a manual START outside any scheduled window. */
    val manuallyStarted: Boolean
        get() = isActive && currentWindow == null && winningOverride?.type == ManualOverrideType.START

    /** When the current active stretch began: the later of the window start and a manual START; null if not active. */
    val activeSince: Instant?
        get() {
            if (!isActive) return null
            val manualStart = winningOverride?.takeIf { it.type == ManualOverrideType.START }?.start?.let(Instant::ofEpochMilli)
            return listOfNotNull(currentWindow?.start, manualStart).maxOrNull()
        }

    /** The resume time when a manual PAUSE is in force; null if not paused or the pause is open-ended. */
    val pausedUntil: Instant?
        get() = if (winningOverride?.type == ManualOverrideType.PAUSE) winningOverrideEnd else null
}

/**
 * Pure schedule logic (no Android types). Layering: a week override replaces the template, the debug
 * test window is added to the scheduled windows, and manual START/PAUSE overrides sit on top (the one
 * with the latest start wins). Windows are built from wall-clock slots with [java.time.ZonedDateTime]
 * semantics, so DST shifts move window edges instead of drifting them.
 */
class EffectiveScheduleCalculator @Inject constructor(private val config: TrackingConfig) {

    fun calculate(inputs: ScheduleInputs, at: Instant, zone: ZoneId): EffectiveSchedule {
        val tl = Timeline(inputs, at, at, zone)
        val now = tl.stateAt(at)
        val horizon = at.plus(Duration.ofDays(config.scheduleLookAheadDays))
        val next = tl.edges().firstOrNull { it > at && it < horizon && tl.stateAt(it).active != now.active }
        return EffectiveSchedule(
            isActive = now.active,
            nextTransition = next,
            currentWindow = tl.windowAt(at),
            winningOverride = now.override,
            winningOverrideEnd = now.override?.let { tl.endOf(it) },
        )
    }

    /** True if capture should have been running at any instant in [from, to]. */
    fun intersectsActive(inputs: ScheduleInputs, from: Instant, to: Instant, zone: ZoneId): Boolean {
        val tl = Timeline(inputs, from, to, zone)
        if (tl.stateAt(from).active || tl.stateAt(to).active) return true
        return tl.edges().any { it > from && it < to && tl.stateAt(it).active }
    }

    /**
     * The sub-intervals of [from, to] during which capture should have been active, ascending, non-overlapping
     * and clipped to [from, to]. Empty when [to] is not after [from] or nothing was active.
     */
    fun activeIntervals(inputs: ScheduleInputs, from: Instant, to: Instant, zone: ZoneId): List<WindowBounds> {
        if (to <= from) return emptyList()
        val tl = Timeline(inputs, from, to, zone)
        val points = listOf(from) + tl.edges().filter { it > from && it < to } + to
        val out = ArrayList<WindowBounds>()
        for (i in 0 until points.size - 1) {
            if (!tl.stateAt(points[i]).active) continue
            val tail = out.lastOrNull()
            if (tail != null && tail.end == points[i]) {
                out[out.lastIndex] = WindowBounds(tail.start, points[i + 1])
            } else {
                out += WindowBounds(points[i], points[i + 1])
            }
        }
        return out
    }

    /** When [override] ends: its stored `end`, else resolved from its end mode; null if open-ended. */
    fun resolveEnd(inputs: ScheduleInputs, override: ManualOverride, zone: ZoneId, at: Instant): Instant? =
        Timeline(inputs, minOf(at, Instant.ofEpochMilli(override.start)), at, zone).endOf(override)

    private data class State(val active: Boolean, val override: ManualOverride?)

    private inner class Timeline(
        private val inputs: ScheduleInputs,
        from: Instant,
        to: Instant,
        private val zone: ZoneId,
    ) {
        private val windows: List<WindowBounds>
        private val effective: List<ManualOverride> =
            inputs.manualOverrides.filter { it.active || it.end != null }

        init {
            val earliestOverride = effective.minOfOrNull { it.start }?.let(Instant::ofEpochMilli)
            val lo = minOf(from, earliestOverride ?: from).minus(Duration.ofDays(config.scheduleLookBackDays))
            val hi = to.plus(Duration.ofDays(config.scheduleLookAheadDays))
            windows = buildWindows(lo, hi)
        }

        private fun buildWindows(lo: Instant, hi: Instant): List<WindowBounds> {
            val raw = ArrayList<WindowBounds>()
            var date = lo.atZone(zone).toLocalDate()
            val last = hi.atZone(zone).toLocalDate()
            while (!date.isAfter(last)) {
                val weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                for (slot in 0 until Schedule.SLOTS_PER_DAY) {
                    val start = slotStart(date, slot)
                    val end = slotStart(date, slot + 1)
                    // Wall-clock slots can collapse or overlap around a DST change; the merge below absorbs that.
                    if (start >= end) continue
                    // An edit counts from its save: each piece of the slot follows the schedule in force at its start.
                    val cuts = inputs.edits.map { it.atMs }
                        .filter { it > start.toEpochMilli() && it < end.toEpochMilli() }
                        .distinct().sorted().map(Instant::ofEpochMilli)
                    var pieceStart = start
                    for (pieceEnd in cuts + end) {
                        if (inputs.scheduleAt(weekStart, pieceStart).isActive(date.dayOfWeek, slot)) {
                            raw += WindowBounds(pieceStart, pieceEnd)
                        }
                        pieceStart = pieceEnd
                    }
                }
                date = date.plusDays(1)
            }
            inputs.testWindowStartMs?.let {
                val start = Instant.ofEpochMilli(it)
                raw += WindowBounds(start, start.plusMillis(config.debugTestWindowLengthMs))
            }
            raw.sortBy { it.start }
            val merged = ArrayList<WindowBounds>()
            for (w in raw) {
                val tail = merged.lastOrNull()
                if (tail != null && w.start <= tail.end) {
                    if (w.end > tail.end) merged[merged.lastIndex] = WindowBounds(tail.start, w.end)
                } else {
                    merged += w
                }
            }
            return merged
        }

        private fun slotStart(date: LocalDate, slot: Int): Instant = slotInstant(date, slot, zone)

        fun windowAt(t: Instant): WindowBounds? = windows.firstOrNull { t >= it.start && t < it.end }

        fun endOf(o: ManualOverride): Instant? {
            o.end?.let { return Instant.ofEpochMilli(it) }
            val start = Instant.ofEpochMilli(o.start)
            return when (o.endMode) {
                ManualOverrideEndMode.UNTIL_STOPPED, ManualOverrideEndMode.DURATION -> null
                ManualOverrideEndMode.END_OF_DAY -> {
                    val oz = runCatching { ZoneId.of(o.zoneId) }.getOrDefault(zone)
                    start.atZone(oz).toLocalDate().plusDays(1).atStartOfDay(oz).toInstant()
                }
                ManualOverrideEndMode.NEXT_WINDOW -> windows.firstOrNull { it.start > start }?.start
            }
        }

        fun stateAt(t: Instant): State {
            val ms = t.toEpochMilli()
            val winner = effective
                .filter { it.start <= ms && (endOf(it)?.let { e -> t < e } ?: true) }
                .maxWithOrNull(compareBy<ManualOverride> { it.start }.thenBy { it.id })
            return if (winner != null) {
                State(winner.type == ManualOverrideType.START, winner)
            } else {
                State(windowAt(t) != null, null)
            }
        }

        /** Every instant at which the scheduled or manual state could change, ascending. */
        fun edges(): List<Instant> {
            val all = ArrayList<Instant>()
            for (w in windows) {
                all += w.start
                all += w.end
            }
            for (o in effective) {
                all += Instant.ofEpochMilli(o.start)
                endOf(o)?.let { all += it }
            }
            return all.distinct().sorted()
        }
    }
}
