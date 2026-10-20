package me.tarry.app.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun extendOpenGapMovesTheEndOfTheOpenGapOnly() = runBlocking<Unit> {
        assertThat(recorder.extendOpenGap(clock.now + 5)).isFalse()
        recorder.reportOutage(noFine)
        assertThat(recorder.extendOpenGap(clock.now + 5)).isTrue()

        assertThat(gaps().single().end).isEqualTo(clock.now + 5)
    }
}
