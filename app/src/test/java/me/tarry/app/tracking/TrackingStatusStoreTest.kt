package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.model.Schedule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class TrackingStatusStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun store(name: String = "tracking_status.preferences_pb") =
        TrackingStatusStore(createTrackingStatusDataStore(scope) { folder.root.resolve(name) })

    @Test
    fun roundTripsAndClears() = runBlocking {
        val s = store()
        assertThat(s.current()).isEqualTo(TrackingStatus())
        s.setLastHeartbeat(123L)
        s.setCaptureMode(CaptureMode.STATIONARY)
        s.setActiveSessionId(7L)
        s.setAttentionCause(AttentionCause.LOCATION_OFF)
        s.setOpenHealthGapId("gap-9")
        s.setDebugTestWindowStart(555L)
        assertThat(s.current()).isEqualTo(
            TrackingStatus(123L, CaptureMode.STATIONARY, 7L, AttentionCause.LOCATION_OFF, "gap-9", 555L),
        )
        s.clearSessionState()
        s.setAttentionCause(null)
        assertThat(s.current()).isEqualTo(TrackingStatus(openHealthGapId = "gap-9", debugTestWindowStartMs = 555L))
    }

    private val week = LocalDate.of(2026, 10, 19)
    private val day = 24 * 60 * 60 * 1000L
    private val painted = Schedule.EMPTY.withSlots(java.time.DayOfWeek.MONDAY, 20..21, true)

    @Test
    fun scheduleEditsRoundTripInAllShapesAndSurviveSessionClear() = runBlocking<Unit> {
        val s = store()
        val edits = listOf(
            ScheduleEdit(1_700_000_000_000L, Schedule.MON_FRI_7_TO_17),
            ScheduleEdit(1_700_000_100_000L, Schedule.MON_FRI_7_TO_17, WeekBefore(week, null)),
            ScheduleEdit(1_700_000_200_000L, painted, WeekBefore(week, Schedule.EMPTY)),
        )
        for (e in edits) s.appendScheduleEdit(e, retentionDays = 14, max = 50)
        assertThat(s.current().scheduleEdits).isEqualTo(edits)
        s.clearSessionState()
        assertThat(s.current().scheduleEdits).isEqualTo(edits)
    }

    @Test
    fun appendPrunesByAgeAndCount() = runBlocking<Unit> {
        val s = store()
        s.appendScheduleEdit(ScheduleEdit(1 * day, painted), retentionDays = 14, max = 3)
        s.appendScheduleEdit(ScheduleEdit(10 * day, painted), retentionDays = 14, max = 3)
        s.appendScheduleEdit(ScheduleEdit(20 * day, painted), retentionDays = 14, max = 3)
        assertThat(s.current().scheduleEdits.map { it.atMs }).containsExactly(10 * day, 20 * day).inOrder()
        s.appendScheduleEdit(ScheduleEdit(21 * day, painted), retentionDays = 14, max = 3)
        s.appendScheduleEdit(ScheduleEdit(22 * day, painted), retentionDays = 14, max = 3)
        assertThat(s.current().scheduleEdits.map { it.atMs }).containsExactly(20 * day, 21 * day, 22 * day).inOrder()
    }

    @Test
    fun malformedScheduleEditsReadAsEmptyAndTheNextAppendStartsFresh() = runBlocking<Unit> {
        val ds = createTrackingStatusDataStore(scope) { folder.root.resolve("bad.preferences_pb") }
        val s = TrackingStatusStore(ds)
        val key = androidx.datastore.preferences.core.stringPreferencesKey("schedule_edits")
        for (junk in listOf("garbage", "1|2|3", "x|AAAA||", "1|!!notbase64!!||", "1|AAAA|not-a-date|")) {
            ds.edit { it[key] = junk }
            assertThat(s.current().scheduleEdits).isEmpty()
        }
        val good = ScheduleEdit(5 * day, painted)
        s.appendScheduleEdit(good, retentionDays = 14, max = 50)
        assertThat(s.current().scheduleEdits).containsExactly(good)
    }

    @Test
    fun segmentationFailureIsRecordedAndClearedBySuccess() = runBlocking {
        val s = store()
        s.recordSegmentationFailure(100L, "db closed")
        assertThat(s.current().segmentationError).isEqualTo(SegmentationError(100L, "db closed"))
        s.recordSegmentationSuccess(200L)
        assertThat(s.current().segmentationError).isNull()
        assertThat(s.current().lastSegmentationSuccessMs).isEqualTo(200L)
    }

    @Test
    fun corruptFileFallsBackToDefaults() = runBlocking {
        folder.root.resolve("corrupt.preferences_pb").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val s = store("corrupt.preferences_pb")
        assertThat(s.current()).isEqualTo(TrackingStatus())
        s.setActiveSessionId(1L)
        assertThat(s.current().activeSessionId).isEqualTo(1L)
    }
}
