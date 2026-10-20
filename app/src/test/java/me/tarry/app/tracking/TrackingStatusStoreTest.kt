package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @Test
    fun corruptFileFallsBackToDefaults() = runBlocking {
        folder.root.resolve("corrupt.preferences_pb").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val s = store("corrupt.preferences_pb")
        assertThat(s.current()).isEqualTo(TrackingStatus())
        s.setActiveSessionId(1L)
        assertThat(s.current().activeSessionId).isEqualTo(1L)
    }
}
