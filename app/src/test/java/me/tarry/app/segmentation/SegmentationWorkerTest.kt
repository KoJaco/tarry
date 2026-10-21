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
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(), Configuration.Builder().build())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runner = SegmentationRunner(
            SegmentationStore(db), db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(),
            db.placeDao(), db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao(), FakeWindowChecker(),
            SegmentationParams(),
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
    }

    private fun work(attempt: Int): ListenableWorker =
        TestListenableWorkerBuilder<SegmentationWorker>(ApplicationProvider.getApplicationContext())
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SegmentationWorker(
                        appContext, workerParameters, runner, FakeClock(now = 1_700_000_000_000L),
                        SegmentationScheduler(appContext, TrackingConfig()),
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
}
