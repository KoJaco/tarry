package me.tarry.app.tracking

import java.time.Instant
import javax.inject.Inject

/** Answers when capture should have been running between two instants. */
interface ScheduleWindowChecker {
    /** The sub-intervals of [from, to] during which capture should have been active, ascending and non-overlapping. */
    suspend fun activeIntervals(from: Instant, to: Instant): List<WindowBounds>
}

/** Backed by the stored schedule, week overrides, manual overrides and the debug test window. */
class EffectiveScheduleWindowChecker @Inject constructor(
    private val inputsProvider: ScheduleInputsProvider,
    private val calculator: EffectiveScheduleCalculator,
    private val clock: TrackingClock,
) : ScheduleWindowChecker {
    override suspend fun activeIntervals(from: Instant, to: Instant): List<WindowBounds> {
        val zone = clock.zone()
        return calculator.activeIntervals(inputsProvider.inputs(from, to, zone), from, to, zone)
    }
}
