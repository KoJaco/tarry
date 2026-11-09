package me.tarry.app.segmentation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
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
class PingRetentionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_700_000_000_000L
    private val params = SegmentationParams()
    private lateinit var db: TarryDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var retention: PingRetention

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") },
        )
        retention = PingRetention(
            settings, db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(), params, FakeClock(now = now),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (db.isOpen) db.close()
    }

    private suspend fun session(start: Long, end: Long?) =
        db.trackingSessionDao().upsert(TrackingSession(start = start, end = end, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE))

    private suspend fun ping(session: Long, timestamp: Long) {
        db.locationPingDao().upsert(
            LocationPing(timestamp = timestamp, zoneId = "UTC", lat = 0.0, lng = 0.0, accuracyM = 5f, source = PingSource.FUSED, sessionId = session),
        )
    }

    private suspend fun remaining(): List<Long> =
        db.locationPingDao().getInRange(0, Long.MAX_VALUE).map { it.timestamp }

    @Test
    fun deletesPingsOlderThanTheRetentionAndKeepsTheRest() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(30)
        val s = session(now - 100 * day, now - 99 * day)
        ping(s, now - 100 * day)
        ping(s, now - 31 * day)
        ping(s, now - 29 * day)

        assertThat(retention.prune()).isEqualTo(2)

        assertThat(remaining()).containsExactly(now - 29 * day)
    }

    @Test
    fun foreverDeletesNothing() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(null)
        val s = session(now - 500 * day, now - 499 * day)
        ping(s, now - 500 * day)

        assertThat(retention.prune()).isEqualTo(0)

        assertThat(remaining()).hasSize(1)
    }

    @Test
    fun theOpenSessionIsNeverCutIntoEvenWhenItIsOlderThanTheRetention() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(30)
        val closed = session(now - 100 * day, now - 99 * day)
        ping(closed, now - 100 * day)
        val open = session(now - 40 * day, null)
        ping(open, now - 40 * day)
        ping(open, now - 35 * day)

        assertThat(retention.prune()).isEqualTo(1)

        assertThat(remaining()).containsExactly(now - 40 * day, now - 35 * day)
    }

    @Test
    fun theRangeTheNextSegmentationRunReadsIsKept() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(30)
        val s = session(now - 100 * day, now - 50 * day)
        ping(s, now - 60 * day)
        ping(s, now - 45 * day)
        ping(s, now - 40 * day)
        // Next run starts at checkpoint minus the overlap: 45 days ago plus a little.
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = now - 45 * day + params.overlapMs))

        assertThat(retention.prune()).isEqualTo(1)

        assertThat(remaining()).containsExactly(now - 45 * day, now - 40 * day)
    }

    @Test
    fun cutoffIsTheEarliestOfTheRetentionAndEveryBound() {
        assertThat(PingRetention.cutoff(now, 30, null, null)).isEqualTo(now - 30 * day)
        assertThat(PingRetention.cutoff(now, 30, now - 5 * day, now - 6 * day)).isEqualTo(now - 30 * day)
        assertThat(PingRetention.cutoff(now, 30, now - 40 * day, now - 6 * day)).isEqualTo(now - 40 * day)
        assertThat(PingRetention.cutoff(now, 30, now - 5 * day, now - 50 * day)).isEqualTo(now - 50 * day)
    }
}
