package me.tarry.app.tracking

import java.time.Instant

/** Answers whether any part of [from, to] lies inside an active schedule window. */
interface ScheduleWindowChecker {
    fun isInsideWindow(from: Instant, to: Instant): Boolean
}

/** Provisional binding until the schedule calculator exists. */
// TODO(2a-T6): replace with a checker backed by EffectiveScheduleCalculator.
class AlwaysInsideWindowChecker @javax.inject.Inject constructor() : ScheduleWindowChecker {
    override fun isInsideWindow(from: Instant, to: Instant): Boolean = true
}
