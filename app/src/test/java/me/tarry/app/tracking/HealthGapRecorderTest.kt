package me.tarry.app.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.repository.TimelineRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HealthGapRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val config = TrackingConfig()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: TarryDatabase
    private lateinit var timeline: TimelineRepository
    private lateinit var store: TrackingStatusStore
    private val alerts = FakeAlertNotifier()
    private val clock = FakeClock(now = 10_000_000L)
    private lateinit var recorder: HealthGapRecorder

    private val noFine = HealthIssue(AttentionCause.NO_FINE_LOCATION, GapCause.PERMISSION_LOST)
    private val noBackground = HealthIssue(AttentionCause.NO_BACKGROUND_LOCATION, GapCause.PERMISSION_LOST)
    private val killed = HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.SERVICE_KILLED)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeline = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        recorder = HealthGapRecorder(store, timeline, alerts, clock, config)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        db.close()
    }

    private suspend fun gaps() = timeline.gapsInRange(0, Long.MAX_VALUE)

    @Test
    fun outageOpensAGapAtTheLastHeartbeatAndStoresItsId() = runBlocking<Unit> {
        store.setLastHeartbeat(clock.now - 300_000)
        recorder.reportOutage(noFine)

        val gap = gaps().single()
        assertThat(gap.start).isEqualTo(clock.now - 300_000)
        assertThat(gap.end).isEqualTo(clock.now)
        assertThat(gap.cause).isEqualTo(GapCause.PERMISSION_LOST)
        assertThat(gap.zoneId).isEqualTo(clock.zone.id)
        assertThat(store.current().openHealthGapId).isEqualTo(gap.id)
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_FINE_LOCATION)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_FINE_LOCATION)
    }

    @Test
    fun outageWithoutHeartbeatStartsNow() = runBlocking<Unit> {
        recorder.reportOutage(killed)

        assertThat(gaps().single().start).isEqualTo(clock.now)
    }

    @Test
    fun staleHeartbeatIsClampedToTheStartOfTheActiveStretch() = runBlocking<Unit> {
        store.setLastHeartbeat(clock.now - 5_000_000L)
        recorder.reportOutage(killed, notBeforeMs = clock.now - 600_000)

        assertThat(gaps().single().start).isEqualTo(clock.now - 600_000)
    }

    @Test
    fun repeatedChecksExtendTheSameGapWithoutANewAlert() = runBlocking<Unit> {
        recorder.reportOutage(noFine)
        val id = store.current().openHealthGapId
        clock.now += 900_000
        recorder.reportOutage(noFine)

        val gap = gaps().single()
        assertThat(gap.id).isEqualTo(id)
        assertThat(gap.end).isEqualTo(clock.now)
        assertThat(alerts.posted).hasSize(1)
    }

    @Test
    fun aDifferentCauseWithinTheSameGapCauseUpdatesTheAlertOnly() = runBlocking<Unit> {
        recorder.reportOutage(noFine)
        recorder.reportOutage(noBackground)

        assertThat(gaps()).hasSize(1)
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_FINE_LOCATION, AttentionCause.NO_BACKGROUND_LOCATION)
    }

    @Test
    fun aDifferentGapCauseClosesTheOldGapAndOpensANewOne() = runBlocking<Unit> {
        recorder.reportOutage(noFine)
        val first = store.current().openHealthGapId
        clock.now += 20 * 60_000L
        recorder.reportOutage(killed)

        val all = gaps()
        assertThat(all).hasSize(2)
        assertThat(all.first { it.id == first }.end).isEqualTo(clock.now)
        assertThat(store.current().openHealthGapId).isNotEqualTo(first)
    }

    @Test
    fun recoveryEndsTheGapClearsTheIdCauseAndAlert() = runBlocking<Unit> {
        recorder.reportOutage(noFine)
        clock.now += 120_000
        recorder.recover(clock.now)

        assertThat(gaps().single().end).isEqualTo(clock.now)
        assertThat(store.current().openHealthGapId).isNull()
        assertThat(store.current().attentionCause).isNull()
        assertThat(alerts.clearCalls).isEqualTo(1)
    }

    @Test
    fun recoverWithNothingOpenDoesNothing() = runBlocking<Unit> {
        recorder.recover()

        assertThat(alerts.clearCalls).isEqualTo(0)
    }

    @Test
    fun anAdjacentGapOfTheSameCauseIsAdoptedNotDuplicated() = runBlocking<Unit> {
        // Crash recovery wrote SERVICE_KILLED [now-10min, now]; the health check then reports the same outage.
        val crash = Gap(
            start = clock.now - 600_000, end = clock.now, zoneId = clock.zone.id,
            cause = GapCause.SERVICE_KILLED, updatedAt = 1,
        )
        timeline.saveGap(crash)
        clock.now += 30_000
        recorder.reportOutage(killed)

        val gap = gaps().single()
        assertThat(gap.id).isEqualTo(crash.id)
        assertThat(gap.start).isEqualTo(crash.start)
        assertThat(gap.end).isEqualTo(clock.now)
        assertThat(store.current().openHealthGapId).isEqualTo(crash.id)
    }

    @Test
    fun anOverlappingGapOfAnotherCauseClampsTheNewStart() = runBlocking<Unit> {
        timeline.saveGap(
            Gap(
                start = clock.now - 600_000, end = clock.now - 100_000, zoneId = clock.zone.id,
                cause = GapCause.LOCATION_OFF, updatedAt = 1,
            ),
        )
        store.setLastHeartbeat(clock.now - 600_000)
        recorder.reportOutage(killed)

        val killedGap = gaps().first { it.cause == GapCause.SERVICE_KILLED }
        assertThat(killedGap.start).isEqualTo(clock.now - 100_000)
    }

    @Test
    fun adoptOpenGapExtendsOnlyThroughATouchingFirstIntervalAndClearsTheId() = runBlocking<Unit> {
        fun iv(a: Long, b: Long) = WindowBounds(Instant.ofEpochMilli(a), Instant.ofEpochMilli(b))
        store.setLastHeartbeat(clock.now - 1_000)
        recorder.reportOutage(noFine)
        val open = gaps().single()

        val rest = recorder.adoptOpenGap(listOf(iv(clock.now, clock.now + 5_000), iv(clock.now + 90_000_000, clock.now + 91_000_000)))

        assertThat(rest).containsExactly(iv(clock.now + 90_000_000, clock.now + 91_000_000))
        val gap = gaps().single()
        assertThat(gap.id).isEqualTo(open.id)
        assertThat(gap.end).isEqualTo(clock.now + 5_000)
        assertThat(store.current().openHealthGapId).isNull()
        // Not touching: nothing is merged and the id is still cleared.
        recorder.reportOutage(killed)
        val untouched = recorder.adoptOpenGap(listOf(iv(clock.now + 50_000_000, clock.now + 51_000_000)))
        assertThat(untouched).hasSize(1)
        assertThat(store.current().openHealthGapId).isNull()
    }

    @Test
    fun outageWithoutHeartbeatStartsAtTheWindowStart() = runBlocking<Unit> {
        recorder.reportOutage(killed, notBeforeMs = clock.now - 600_000)

        assertThat(gaps().single().start).isEqualTo(clock.now - 600_000)
    }

    @Test
    fun concurrentReportsAndRecoveriesKeepASingleGapAndConsistentState() = runBlocking<Unit> {
        store.setLastHeartbeat(clock.now - 300_000)
        // Without serialisation every caller sees "no open Gap" and writes its own.
        (1..20).map {
            async(Dispatchers.Default) {
                recorder.reportOutage(noFine)
            }
        }.awaitAll()

        val gap = gaps().single()
        assertThat(store.current().openHealthGapId).isEqualTo(gap.id)
        assertThat(alerts.posted).hasSize(1)

        (1..10).map { async(Dispatchers.Default) { recorder.closeOutage(clock.now) } }.awaitAll()
        assertThat(alerts.clearCalls).isEqualTo(1)
        assertThat(store.current().openHealthGapId).isNull()
    }

    @Test
    fun attentionOnlyIssueSetsCauseAndAlertsOncePerCauseWithoutAGap() = runBlocking<Unit> {
        val warning = noBackground.copy(recordGap = false)
        recorder.reportOutage(warning)
        recorder.reportOutage(warning)

        assertThat(gaps()).isEmpty()
        assertThat(store.current().openHealthGapId).isNull()
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_BACKGROUND_LOCATION)

        recorder.reportOutage(noFine.copy(recordGap = false))
        assertThat(alerts.posted).hasSize(2)
    }

    @Test
    fun closeOutageLeavesAnAttentionOnlyCauseButClosesAGap() = runBlocking<Unit> {
        recorder.reportAttention(AttentionCause.NO_BACKGROUND_LOCATION)
        recorder.closeOutage()
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.clearCalls).isEqualTo(0)

        recorder.recover()
        recorder.reportOutage(killed)
        recorder.closeOutage()
        assertThat(store.current().attentionCause).isNull()
        assertThat(store.current().openHealthGapId).isNull()
    }

    @Test
    fun recoverCauseClearsOnlyTheMatchingCauseAndClosesItsGap() = runBlocking<Unit> {
        val denied = HealthIssue(AttentionCause.EXACT_ALARM_DENIED, GapCause.SERVICE_KILLED)
        recorder.reportOutage(denied)
        recorder.recoverCause(AttentionCause.NO_FINE_LOCATION)
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.EXACT_ALARM_DENIED)

        clock.now += 60_000
        recorder.recoverCause(AttentionCause.EXACT_ALARM_DENIED)
        assertThat(store.current().attentionCause).isNull()
        assertThat(store.current().openHealthGapId).isNull()
        assertThat(gaps().single().end).isEqualTo(clock.now)
        assertThat(alerts.clearCalls).isEqualTo(1)
    }

    @Test
    fun uncaughtExceptionInTheCaptureScopeIsLoggedAndRecordedAsAnOutageNotACrash() = runBlocking<Unit> {
        store.setActiveSessionId(1)
        val scope = createCaptureScope({ recorder }, { store }, Dispatchers.Unconfined)
        scope.launch { throw IllegalStateException("boom") }
        withTimeout(5_000) { while (alerts.posted.isEmpty()) delay(10) }

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.SERVICE_NOT_RUNNING)
        assertThat(gaps().single().cause).isEqualTo(GapCause.SERVICE_KILLED)
        scope.cancel()
    }

    @Test
    fun aCancelledPlayServicesTaskIsAFailureNotCoroutineCancellation() = runBlocking<Unit> {
        val result = async(Dispatchers.Unconfined) { runCatching { com.google.android.gms.tasks.Tasks.forCanceled<Unit>().awaitDone() } }

        val failure = withTimeout(5_000) { result.await() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(TaskCancelledException::class.java)
        assertThat(failure).isNotInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    @Test
    fun anAttentionOnlyReportClosesAnAlreadyOpenGapAtNow() = runBlocking<Unit> {
        store.setLastHeartbeat(clock.now - 300_000)
        recorder.reportOutage(noFine)
        clock.now += 120_000

        recorder.reportOutage(noBackground.copy(recordGap = false))

        assertThat(gaps().single().end).isEqualTo(clock.now)
        assertThat(store.current().openHealthGapId).isNull()
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.posted.last()).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
    }

    @Test
    fun anOutageIsNotStretchedAcrossAnInactiveDiscontinuity() = runBlocking<Unit> {
        val day = 24 * 3_600_000L
        val firstStretchStart = clock.now - 3_600_000
        recorder.reportOutage(noFine, notBeforeMs = firstStretchStart)
        val first = gaps().single()
        // Next day's window: the earlier stretch ended long ago.
        clock.now += day
        store.setLastHeartbeat(clock.now - 600_000)
        recorder.reportOutage(noFine, notBeforeMs = clock.now - 3_600_000)

        val all = gaps().sortedBy { it.start }
        assertThat(all).hasSize(2)
        assertThat(all[0].id).isEqualTo(first.id)
        assertThat(all[0].end).isEqualTo(first.end)
        assertThat(all[1].start).isEqualTo(clock.now - 600_000)
        assertThat(store.current().openHealthGapId).isEqualTo(all[1].id)
    }

    @Test
    fun uncaughtExceptionWithNoOpenSessionIsOnlyLogged() = runBlocking<Unit> {
        val scope = createCaptureScope({ recorder }, { store }, Dispatchers.Unconfined)
        scope.launch { throw IllegalStateException("state repository blew up") }
        delay(300)

        assertThat(gaps()).isEmpty()
        assertThat(alerts.posted).isEmpty()
        assertThat(store.current().attentionCause).isNull()
        scope.cancel()
    }

    @Test
    fun aThrowingOutageReportCannotLoopThroughTheHandler() = runBlocking<Unit> {
        var reports = 0
        val scope = createCaptureScope({ recorder }, { reports++; throw AssertionError("status store down") }, Dispatchers.Unconfined)
        scope.launch { throw IllegalStateException("boom") }
        delay(300)

        assertThat(reports).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun attentionOnlyCauseIsReportedOnlyWhileNoGapIsOpen() = runBlocking<Unit> {
        assertThat(recorder.attentionOnlyCause()).isNull()
        recorder.reportAttention(AttentionCause.NO_FINE_LOCATION)
        assertThat(recorder.attentionOnlyCause()).isEqualTo(AttentionCause.NO_FINE_LOCATION)
        recorder.recover()
        recorder.reportOutage(noFine)
        assertThat(recorder.attentionOnlyCause()).isNull()
    }
}
