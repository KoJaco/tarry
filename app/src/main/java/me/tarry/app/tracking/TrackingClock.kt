package me.tarry.app.tracking

import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Qualifier

/** Time source for capture code, so tests control "now" and the capture-time zone. */
interface TrackingClock {
    fun nowMs(): Long

    /** Zone at capture time (rule 3); re-read on every use because the device zone can change. */
    fun zone(): ZoneId
}

class SystemTrackingClock @Inject constructor() : TrackingClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Qualifies the app-level, single-threaded scope that runs capture work (see TrackingModule). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CaptureScope
