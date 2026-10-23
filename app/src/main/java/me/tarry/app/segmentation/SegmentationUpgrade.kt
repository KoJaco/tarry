package me.tarry.app.segmentation

import me.tarry.app.tracking.TrackingClock
import me.tarry.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-segments stored data after an app upgrade changed the algorithm. Bump [SEGMENTATION_VERSION] with every change
 * to `:segmentation` that alters output; at startup, if the stored version is behind, the checkpoint is lowered to the
 * start of the retention window and a run is queued. Frozen rows are never touched by the runner, so edits survive.
 */
@Singleton
class SegmentationUpgrade @Inject constructor(
    private val settings: SettingsRepository,
    private val runner: SegmentationRunner,
    private val scheduler: SegmentationScheduler,
    private val clock: TrackingClock,
) {
    /** Returns true if a re-run was queued. The new version is recorded only after the rewind succeeded. */
    suspend fun runIfNeeded(): Boolean {
        if (settings.segmentationVersion() >= SEGMENTATION_VERSION) return false
        val retentionDays = settings.settings.first().rawPingRetentionDays
        val windowStart = retentionDays?.let { clock.nowMs() - TimeUnit.DAYS.toMillis(it.toLong()) } ?: 0L
        runner.rewindCheckpoint(maxOf(windowStart, 0L))
        settings.setSegmentationVersion(SEGMENTATION_VERSION)
        scheduler.enqueueNow()
        return true
    }

    companion object {
        /**
         * 1 = original algorithm; 2 = dogfood-1 fixes (sustained departure, displacement merge, arrival radius);
         * 3 = indistinguishable known places never split a stay, no zero-length stays;
         * 4 = dogfood-2 B2 (spike rejection, anchor-aware noise, coherent departures, noise-scaled places and merge).
         */
        const val SEGMENTATION_VERSION = 4
    }
}
