package me.tarry.app.tracking

import java.time.Instant
import javax.inject.Inject

/** Answers whether capture should have been running at any point in [from, to]. */
interface ScheduleWindowChecker {
    suspend fun isInsideWindow(from: Instant, to: Instant): Boolean
}

/** Backed by the stored schedule, week overrides, manual overrides and the debug test window. */
class EffectiveScheduleWindowChecker @Inject constructor(
    private val inputsProvider: ScheduleInputsProvider,
    private val calculator: EffectiveScheduleCalculator,
    private val clock: TrackingClock,
) : ScheduleWindowChecker {
    override suspend fun isInsideWindow(from: Instant, to: Instant): Boolean {
        val zone = clock.zone()
        return calculator.intersectsActive(inputsProvider.inputs(from, to, zone), from, to, zone)
    }
}
