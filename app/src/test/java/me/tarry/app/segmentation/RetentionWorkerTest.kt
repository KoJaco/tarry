package me.tarry.app.segmentation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.TrackingConfig
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.segmentation.SegmentationParams
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RetentionWorkerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val config = TrackingConfig()
    private val now = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)
    private lateinit var context: Context
    private lateinit var db: TarryDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") },
        )
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        scope.cancel()
        if (db.isOpen) db.close()
    }

    private fun worker(): ListenableWorker {
        val retention = PingRetention(
            settings, db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(),
            SegmentationParams(), FakeClock(now = now), config,
        )
        return TestListenableWorkerBuilder<RetentionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    RetentionWorker(appContext, workerParameters, retention)
            })
            .build()
    }

    @Test
    fun enqueueIsUniquePeriodicWithTheConfiguredPeriodAndKeepsTheExistingSchedule() {
        RetentionWorker.enqueue(context, config)
        val workManager = WorkManager.getInstance(context)
        val first = workManager.getWorkInfosForUniqueWork(RetentionWorker.UNIQUE_NAME).get().single()
        RetentionWorker.enqueue(context, config)

        val info = workManager.getWorkInfosForUniqueWork(RetentionWorker.UNIQUE_NAME).get().single()
        assertThat(info.id).isEqualTo(first.id)
        assertThat(info.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(config.retentionWorkerPeriodHours))
        assertThat(RetentionWorker.UNIQUE_NAME).isEqualTo("ping_retention")
    }

    @Test
    fun aRunPrunesAndReturnsSuccess() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(30)
        val s = db.trackingSessionDao().upsert(
            TrackingSession(start = now - 100 * day, end = now - 99 * day, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE),
        )
        db.locationPingDao().upsert(
            LocationPing(timestamp = now - 100 * day, zoneId = "UTC", lat = 0.0, lng = 0.0, accuracyM = 5f, source = PingSource.FUSED, sessionId = s),
        )
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = now))

        assertThat(worker().startWork().get()).isEqualTo(ListenableWorker.Result.success())

        assertThat(db.locationPingDao().getInRange(0, Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun anExceptionRetriesRatherThanFailingForGood() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(30)
        val broken = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(me.tarry.core.data.db.dao.SegmentationCheckpointDao::class.java),
        ) { _, _, _ -> throw IllegalStateException("database unavailable") } as me.tarry.core.data.db.dao.SegmentationCheckpointDao
        val retention = PingRetention(
            settings, db.locationPingDao(), db.trackingSessionDao(), broken, SegmentationParams(), FakeClock(now = now), config,
        )
        val failing = TestListenableWorkerBuilder<RetentionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    RetentionWorker(appContext, workerParameters, retention)
            })
            .build()

        assertThat(failing.startWork().get()).isEqualTo(ListenableWorker.Result.retry())
    }
}
