package me.tarry.app.tracking

import me.tarry.core.data.model.Schedule
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.repository.TrackingRepository
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** Loads [ScheduleInputs] from Room and the status store. */
class ScheduleInputsProvider @Inject constructor(
    private val schedules: ScheduleRepository,
    private val trackingRepo: TrackingRepository,
    private val statusStore: TrackingStatusStore,
    private val config: TrackingConfig,
    private val clock: TrackingClock,
) {
    /** First launch: stores the Mon-Fri 07:00-17:00 preset if there is no template yet. */
    suspend fun seedTemplateIfMissing() {
        if (schedules.template() == null) schedules.saveTemplate(Schedule.MON_FRI_7_TO_17, clock.nowMs())
    }

    /** Inputs covering [from, to] plus the calculator's look-back/look-ahead margins. */
    suspend fun inputs(from: Instant, to: Instant, zone: ZoneId): ScheduleInputs {
        seedTemplateIfMissing()
        val lo = from.minus(Duration.ofDays(config.scheduleLookBackDays + WEEK_MARGIN_DAYS))
        val hi = to.plus(Duration.ofDays(config.scheduleLookAheadDays + WEEK_MARGIN_DAYS))
        val overrides = mutableMapOf<LocalDate, Schedule>()
        var week = lo.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastDay = hi.atZone(zone).toLocalDate()
        while (!week.isAfter(lastDay)) {
            schedules.override(week.toString())?.let { overrides[week] = it }
            week = week.plusWeeks(1)
        }
        val status = statusStore.current()
        return ScheduleInputs(
            template = checkNotNull(schedules.template()) { "template was just seeded" },
            weekOverrides = overrides,
            manualOverrides = trackingRepo.overridesInRange(lo.toEpochMilli(), hi.toEpochMilli()),
            testWindowStartMs = status.debugTestWindowStartMs,
            edit = status.scheduleEdit,
        )
    }

    private companion object {
        const val WEEK_MARGIN_DAYS = 1L
    }
}
