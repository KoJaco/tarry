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
import me.tarry.app.segmentation.SegmentationRunner
import me.tarry.app.segmentation.SegmentationUpgrade
import me.tarry.app.tracking.FakeWindowChecker
import me.tarry.app.tracking.WindowBounds
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.entity.SegmentationCheckpoint
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

        // Every day: 3 to 7 visits, the last one running to the window end, in order, at least one job block.
        for (d in workingDays) {
            val visits = snap.visits.filter { day(it.start) == d }.sortedBy { it.start }
            assertThat(visits.size).isIn(3..7)
            assertThat(snap.jobBlocks.count { day(it.start) == d }).isAtLeast(1)
            assertThat(visits.last().end).isEqualTo(window(d).last)
            assertThat(visits.zipWithNext().all { (a, b) -> a.end <= b.start }).isTrue()
            // Sessions and the known gaps between them tile the schedule window exactly: nothing uncovered, nothing past it.
            val w = window(d)
            val covered = (snap.sessions.filter { day(it.start) == d }.map { it.start..it.end!! } +
                snap.gaps.filter { it.cause == GapCause.SERVICE_KILLED && day(it.start) == d }.map { it.start..it.end }).sortedBy { it.first }
            assertThat(covered.first().first).isEqualTo(w.first)
            assertThat(covered.last().last).isEqualTo(w.last)
            assertThat(covered.zipWithNext().all { (a, b) -> a.last == b.first }).isTrue()
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

    /** The schedule window of [d] in epoch ms: 07:00-17:00, Friday 07:00-14:00. */
    private fun window(d: LocalDate): LongRange {
        val end = if (d.dayOfWeek == java.time.DayOfWeek.FRIDAY) 14 else 17
        return d.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()..d.atTime(end, 0).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun resegmentingTheRestoredDemoAddsNoNoSessionOrExtraNoFixGaps() = runBlocking<Unit> {
        val file = File(folder.root, "demo.json")
        file.outputStream().use { BackupCodec.encode(DemoBackup.build(today), it) }
        manager.restore(manager.preview { file.inputStream() })
        val before = store.snapshot()

        val checker = FakeWindowChecker().apply {
            intervals = DemoBackup.workingDays(today).map {
                val w = window(it)
                WindowBounds(Instant.ofEpochMilli(w.first), Instant.ofEpochMilli(w.last))
            }
        }
        val runner = SegmentationRunner(
            SegmentationStore(db), db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(),
            db.placeDao(), db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao(), checker, SegmentationParams(),
        )
        // Re-run over the whole history, as an upgrade rewind would, and on until the evening of today.
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = before.sessions.minOf { it.start }))
        runner.run(window(today).last + 30 * 60_000L)

        val gaps = db.gapDao().getInRange(0, Long.MAX_VALUE).filter { !it.deleted }
        assertThat(gaps.filter { it.cause == GapCause.NO_SESSION }).isEmpty()
        assertThat(gaps.count { it.cause == GapCause.NO_FIX }).isAtMost(1)
        assertThat(gaps.count { it.cause == GapCause.SERVICE_KILLED }).isEqualTo(1)
        assertThat(gaps.size).isAtMost(2)
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
