package me.tarry.app.segmentation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeWindowChecker
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.SegmentationCheckpoint
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
class SegmentationUpgradeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val now = 1_700_000_000_000L
    private val day = TimeUnit.DAYS.toMillis(1)
    private lateinit var context: Context
    private lateinit var db: TarryDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var upgrade: SegmentationUpgrade

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") },
        )
        val runner = SegmentationRunner(
            SegmentationStore(db), db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(),
            db.placeDao(), db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao(), FakeWindowChecker(),
            SegmentationParams(),
        )
        upgrade = SegmentationUpgrade(settings, runner, SegmentationScheduler(context, me.tarry.app.tracking.TrackingConfig()), FakeClock(now = now))
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        scope.cancel()
        if (db.isOpen) db.close()
    }

    private fun queuedNow() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(SegmentationScheduler.NOW).get()

    @Test
    fun staleVersionRewindsToTheRetentionWindowRecordsTheVersionAndQueuesARun() = runBlocking<Unit> {
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = now - day))

        assertThat(upgrade.runIfNeeded()).isTrue()

        // Default retention is 90 days.
        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(now - 90 * day)
        assertThat(settings.segmentationVersion()).isEqualTo(SegmentationUpgrade.SEGMENTATION_VERSION)
        assertThat(queuedNow()).isNotEmpty()
    }

    @Test
    fun aDeviceOnThePreviousVersionReRuns() = runBlocking<Unit> {
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = now - day))
        settings.setSegmentationVersion(SegmentationUpgrade.SEGMENTATION_VERSION - 1)

        assertThat(upgrade.runIfNeeded()).isTrue()

        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(now - 90 * day)
        assertThat(settings.segmentationVersion()).isEqualTo(SegmentationUpgrade.SEGMENTATION_VERSION)
    }

    @Test
    fun currentVersionDoesNothing() = runBlocking<Unit> {
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = now - day))
        settings.setSegmentationVersion(SegmentationUpgrade.SEGMENTATION_VERSION)

        assertThat(upgrade.runIfNeeded()).isFalse()

        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(now - day)
        assertThat(queuedNow()).isEmpty()
    }

    @Test
    fun checkpointOlderThanTheWindowIsNotRaised() = runBlocking<Unit> {
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = now - 200 * day))

        upgrade.runIfNeeded()

        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(now - 200 * day)
    }

    @Test
    fun keepForeverRetentionRewindsToTheStart() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(null)
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = now - day))

        upgrade.runIfNeeded()

        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(0L)
    }

    @Test
    fun freshInstallWithoutCheckpointJustRecordsTheVersion() = runBlocking<Unit> {
        assertThat(upgrade.runIfNeeded()).isTrue()

        assertThat(db.segmentationCheckpointDao().getById()).isNull()
        assertThat(settings.segmentationVersion()).isEqualTo(SegmentationUpgrade.SEGMENTATION_VERSION)
    }
}
