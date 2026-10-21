package me.tarry.app.segmentation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.app.tracking.createTrackingStatusDataStore
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionTrigger
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeWindowChecker
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.segmentation.SegmentationParams
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SegmentationWorkerTest {
    private lateinit var db: TarryDatabase
    private lateinit var runner: SegmentationRunner
    private val windowChecker = FakeWindowChecker(inside = false)
    private lateinit var statusStore: TrackingStatusStore
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    @get:org.junit.Rule
    val folder = org.junit.rules.TemporaryFolder()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        statusStore = TrackingStatusStore(createTrackingStatusDataStore(scope) { folder.root.resolve("status.preferences_pb") })
        runner = SegmentationRunner(
            SegmentationStore(db), db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(),
            db.placeDao(), db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao(), FakeWindowChecker(),
            SegmentationParams(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (db.isOpen) db.close()
    }

    private fun work(attempt: Int, tags: List<String> = emptyList()): ListenableWorker =
        TestListenableWorkerBuilder<SegmentationWorker>(ApplicationProvider.getApplicationContext())
            .setRunAttemptCount(attempt)
            .setTags(tags)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SegmentationWorker(
                        appContext, workerParameters, runner, FakeClock(now = 1_700_000_000_000L),
                        SegmentationScheduler(appContext, TrackingConfig()), statusStore, db.trackingSessionDao(), windowChecker,
                    )
            })
            .build()

    @Test
    fun successfulRunReturnsSuccess() = runBlocking<Unit> {
        assertThat(work(0).startWork().get()).isEqualTo(ListenableWorker.Result.success())
        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(1_700_000_000_000L)
        val geocode = WorkManager.getInstance(ApplicationProvider.getApplicationContext<Context>())
            .getWorkInfosForUniqueWork(SegmentationScheduler.GEOCODE).get()
        assertThat(geocode).hasSize(1)
    }

    @Test
    fun failureRetriesThreeTimesThenFails() = runBlocking<Unit> {
        db.close()

        for (attempt in 0 until SegmentationWorker.MAX_RETRIES) {
            assertThat(work(attempt).startWork().get()).isEqualTo(ListenableWorker.Result.retry())
        }
        assertThat(work(SegmentationWorker.MAX_RETRIES).startWork().get()).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun finalFailureIsRecordedAndNextSuccessClearsIt() = runBlocking<Unit> {
        db.close()
        assertThat(work(SegmentationWorker.MAX_RETRIES).startWork().get()).isEqualTo(ListenableWorker.Result.failure())
        val error = statusStore.current().segmentationError
        assertThat(error).isNotNull()
        assertThat(error!!.timeMs).isEqualTo(1_700_000_000_000L)
        assertThat(error.message).isNotEmpty()
    }

    @Test
    fun retryDoesNotRecordAnError() = runBlocking<Unit> {
        db.close()
        work(0).startWork().get()
        assertThat(statusStore.current().segmentationError).isNull()
    }

    @Test
    fun successClearsAnEarlierError() = runBlocking<Unit> {
        statusStore.recordSegmentationFailure(1L, "old")
        work(0).startWork().get()
        assertThat(statusStore.current().segmentationError).isNull()
        assertThat(statusStore.current().lastSegmentationSuccessMs).isEqualTo(1_700_000_000_000L)
    }

    private fun periodicState() = WorkManager.getInstance(ApplicationProvider.getApplicationContext<Context>())
        .getWorkInfosForUniqueWork(SegmentationScheduler.PERIODIC).get().single().state

    @Test
    fun periodicRunWithNoOpenSessionCancelsThePeriodicWork() = runBlocking<Unit> {
        SegmentationScheduler(ApplicationProvider.getApplicationContext(), TrackingConfig()).startPeriodic()

        work(0, listOf(SegmentationScheduler.TAG_PERIODIC)).startWork().get()

        assertThat(periodicState()).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun periodicRunWithAnOpenSessionKeepsRunning() = runBlocking<Unit> {
        db.trackingSessionDao().upsert(TrackingSession(start = 1L, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE))
        SegmentationScheduler(ApplicationProvider.getApplicationContext(), TrackingConfig()).startPeriodic()

        work(0, listOf(SegmentationScheduler.TAG_PERIODIC)).startWork().get()

        assertThat(periodicState()).isNotEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun nonPeriodicRunLeavesThePeriodicWorkAlone() = runBlocking<Unit> {
        SegmentationScheduler(ApplicationProvider.getApplicationContext(), TrackingConfig()).startPeriodic()

        work(0).startWork().get()

        assertThat(periodicState()).isNotEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun periodicRunKeepsRunningWhenAWindowIsActiveButNoSessionExistsYet() = runBlocking<Unit> {
        windowChecker.inside = true
        SegmentationScheduler(ApplicationProvider.getApplicationContext(), TrackingConfig()).startPeriodic()

        work(0, listOf(SegmentationScheduler.TAG_PERIODIC)).startWork().get()

        assertThat(periodicState()).isNotEqualTo(WorkInfo.State.CANCELLED)
    }
}
