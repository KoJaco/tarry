package me.tarry.app.segmentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.awaitCancellation
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.TrackingConfig
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SegmentationSchedulerTest {
    private val config = TrackingConfig()
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SegmentationScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Workers hang instead of finishing: a finished one-time run would not be kept, and these tests look at what was enqueued.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(HangingWorkerFactory).build())
        workManager = WorkManager.getInstance(context)
        scheduler = SegmentationScheduler(context, config)
    }

    @After
    fun tearDown() {
        // Hanging workers are still running; cancel and wait so no WorkManager future is left to be failed by the GC finalizer
        // after Robolectric has closed the work database (that leaked an uncaught coroutine exception into later Compose tests).
        workManager.cancelAllWork().result.get()
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun enqueueNowIsUniqueAndKeepsTheQueuedRun() {
        scheduler.enqueueNow()
        val first = infos(SegmentationScheduler.NOW).single()
        scheduler.enqueueNow()

        assertThat(infos(SegmentationScheduler.NOW).single().id).isEqualTo(first.id)
        assertThat(SegmentationScheduler.NOW).isEqualTo("segmentation-now")
    }

    @Test
    fun periodicRunUsesTheConfiguredPeriodAndKeepsTheExistingSchedule() {
        scheduler.startPeriodic()
        val first = infos(SegmentationScheduler.PERIODIC).single()
        scheduler.startPeriodic()

        val info = infos(SegmentationScheduler.PERIODIC).single()
        assertThat(info.id).isEqualTo(first.id)
        assertThat(info.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(config.segmentationPeriodMin))
        assertThat(SegmentationScheduler.PERIODIC).isEqualTo("segmentation-periodic")
    }

    @Test
    fun stopPeriodicCancelsThePeriodicWork() {
        scheduler.startPeriodic()
        scheduler.stopPeriodic()

        assertThat(infos(SegmentationScheduler.PERIODIC).single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun dailyRunRequiresChargingAndKeepsTheExistingSchedule() {
        scheduler.ensureDaily()
        val first = infos(SegmentationScheduler.DAILY).single()
        scheduler.ensureDaily()

        val info = infos(SegmentationScheduler.DAILY).single()
        assertThat(info.id).isEqualTo(first.id)
        assertThat(info.constraints.requiresCharging()).isTrue()
        assertThat(info.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(config.segmentationDailyPeriodHours))
        assertThat(SegmentationScheduler.DAILY).isEqualTo("segmentation-daily")
    }

    @Test
    fun geocodeIsUniqueNeedsNetworkAndKeepsTheQueuedRun() {
        scheduler.enqueueGeocode()
        val first = infos(SegmentationScheduler.GEOCODE).single()
        scheduler.enqueueGeocode()

        val info = infos(SegmentationScheduler.GEOCODE).single()
        assertThat(info.id).isEqualTo(first.id)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(SegmentationScheduler.GEOCODE).isEqualTo("geocode")
    }

    @Test
    fun triggerMapsCaptureEventsToScheduler() = runBlocking<Unit> {
        val trigger = WorkManagerSegmentationTrigger(scheduler)

        trigger.onCaptureStarted()
        assertThat(infos(SegmentationScheduler.PERIODIC).single().state).isNotEqualTo(WorkInfo.State.CANCELLED)

        trigger.onCaptureStopped()
        assertThat(infos(SegmentationScheduler.PERIODIC).single().state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(infos(SegmentationScheduler.NOW)).hasSize(1)
    }

    @Test
    fun windowEndEnqueuesAOneTimeRun() = runBlocking<Unit> {
        WorkManagerSegmentationTrigger(scheduler).onWindowEnd()

        assertThat(infos(SegmentationScheduler.NOW)).hasSize(1)
        assertThat(infos(SegmentationScheduler.PERIODIC)).isEmpty()
    }
}

private object HangingWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
        object : CoroutineWorker(appContext, workerParameters) {
            override suspend fun doWork(): Result = awaitCancellation()
        }
}
