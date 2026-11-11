package me.tarry.app.export.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.segmentation.SegmentationUpgrade
import me.tarry.app.tracking.FakeClock
import me.tarry.app.ui.timeline.Segment
import me.tarry.app.ui.timeline.coverage
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.SessionEndReason
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The generated demo backup restores through the real [BackupManager] and the restored timeline looks right. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DemoBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: TarryDatabase
    private lateinit var store: BackupStore
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var manager: BackupManager
    private val zone = ZoneId.of(DemoBackup.ZONE)
    private val today = LocalDate.of(2026, 11, 11) // a Wednesday

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        store = BackupStore(db)
        val settingsStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") }
        val declinedStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("declined.preferences_pb") }
        settingsRepo = SettingsRepository(settingsStore)
        manager = BackupManager(
            context, store, settingsStore, declinedStore, settingsRepo, RecordingHooks(),
            FakeClock(now = 1_700_000_000_000L), SegmentationParams(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    @Test
    fun theDemoBackupRestoresIntoACompleteTimeline() = runBlocking<Unit> {
        val file = File(folder.root, "demo.json")
        file.outputStream().use { BackupCodec.encode(DemoBackup.build(today), it) }
        manager.restore(manager.preview { file.inputStream() })
        val snap = store.snapshot()

        val workingDays = DemoBackup.workingDays(today)
        assertThat(workingDays).hasSize(10)
        assertThat(snap.sessions.map { day(it.start) }.toSet()).isEqualTo(workingDays.toSet())
        assertThat(snap.sessions.count { it.endReason == SessionEndReason.KILLED }).isEqualTo(1)
        assertThat(snap.gaps.count { it.cause == GapCause.NO_FIX }).isEqualTo(1)
        assertThat(snap.gaps.count { it.cause == GapCause.SERVICE_KILLED }).isEqualTo(1)
        // The unrecorded stretches are in week one, never on today.
        assertThat(snap.gaps.all { day(it.start) in workingDays.take(5) }).isTrue()
        assertThat(snap.sessions.none { it.end == null }).isTrue()

        // Every day: 3 to 7 visits, ended before 16:30, in order, at least one job block.
        for (d in workingDays) {
            val visits = snap.visits.filter { day(it.start) == d }.sortedBy { it.start }
            assertThat(visits.size).isIn(3..7)
            assertThat(snap.jobBlocks.count { day(it.start) == d }).isAtLeast(1)
            val endLimit = d.atTime(16, 30).atZone(zone).toInstant().toEpochMilli()
            assertThat(visits.maxOf { it.end }).isAtMost(endLimit)
            assertThat(visits.zipWithNext().all { (a, b) -> a.end <= b.start }).isTrue()
        }
        assertThat(snap.visits.any { day(it.start) == today }).isTrue()

        // Needs review: no job block overlaps the visit; two to three, all in the last three days.
        val cov = coverage(snap.visits.map { Segment(it.id, it.start, it.end) }, emptyList(), snap.jobBlocks)
        val review = snap.visits.filter { cov.needsReview(it.id, placeIgnored = false) }
        assertThat(review.size).isIn(2..3)
        assertThat(review.all { day(it.start) in workingDays.takeLast(3) }).isTrue()

        // Trips link visits; pings are dense and belong to their sessions.
        assertThat(snap.trips).isNotEmpty()
        assertThat(snap.trips.all { it.fromVisitId != null && it.toVisitId != null && it.distanceM > 100f }).isTrue()
        assertThat(snap.pings.size).isGreaterThan(2000)
        val bySession = snap.sessions.associateBy { it.id }
        assertThat(snap.pings.all { p -> bySession.getValue(p.sessionId).let { p.timestamp >= it.start && p.timestamp <= it.end!! } }).isTrue()
        assertThat(snap.visits.count { it.freeNotes.isNotEmpty() }).isAtLeast(3)
        assertThat(snap.visits.filter { it.freeNotes.isNotEmpty() }.all { it.frozen }).isTrue()

        // The file carries the current segmentation version and a checkpoint at the last ping, so restore queues no re-run.
        assertThat(settingsRepo.segmentationVersion()).isEqualTo(SegmentationUpgrade.SEGMENTATION_VERSION)
        assertThat(snap.checkpoints.single().processedUntil).isEqualTo(snap.pings.maxOf { it.timestamp })
    }

    @Test
    fun theLatestTenWorkingDaysSkipWeekends() {
        // Sunday 2026-11-15: ten weekdays ending Friday 13 Nov.
        val days = DemoBackup.workingDays(LocalDate.of(2026, 11, 15))
        assertThat(days.last()).isEqualTo(LocalDate.of(2026, 11, 13))
        assertThat(days.first()).isEqualTo(LocalDate.of(2026, 11, 2))
    }

    @Test
    fun everyWeekdayAlignmentBuilds() {
        // Day plans depend on the weekday of the end date; build() requires every day to fit its limits.
        for (offset in 0L until 7) DemoBackup.build(today.plusDays(offset))
    }
}
