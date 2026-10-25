package me.tarry.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.DbTestBase
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionTrigger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackingRepositoryTest : DbTestBase() {

    private fun repo() = TrackingRepository(
        db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
    )

    @Test
    fun saveSession_updateReturnsRealId() = runTest {
        val r = repo()
        val id = r.saveSession(TrackingSession(start = 1, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        assertThat(id).isGreaterThan(0L)
        val original = r.session(id)!!
        val updatedId = r.saveSession(original.copy(end = 99))
        assertThat(updatedId).isEqualTo(id)
        assertThat(r.session(id)!!.end).isEqualTo(99)
    }

    @Test
    fun savePing_updateReturnsRealId() = runTest {
        val r = repo()
        val sid = r.saveSession(TrackingSession(start = 1, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        val id = r.savePing(
            LocationPing(
                timestamp = 5, zoneId = "UTC", lat = 1.0, lng = 2.0, accuracyM = 5f,
                source = PingSource.entries.first(), sessionId = sid,
            ),
        )
        val original = db.locationPingDao().getById(id)!!
        val updatedId = r.savePing(original.copy(accuracyM = 9f))
        assertThat(updatedId).isEqualTo(id)
        assertThat(db.locationPingDao().getById(id)!!.accuracyM).isEqualTo(9f)
    }

    @Test
    fun sessionsAreListedNewestFirstWithPingCounts() = runTest {
        val r = repo()
        val older = r.saveSession(TrackingSession(start = 10, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE))
        val newer = r.saveSession(TrackingSession(start = 20, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        val empty = r.saveSession(TrackingSession(start = 30, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        for ((i, sid) in listOf(older, newer, newer).withIndex()) {
            r.savePing(
                LocationPing(
                    timestamp = 100L + i, zoneId = "UTC", lat = 1.0, lng = 2.0, accuracyM = 5f,
                    source = PingSource.FUSED, sessionId = sid,
                ),
            )
        }

        assertThat(r.observeSessions().first().map { it.id }).containsExactly(empty, newer, older).inOrder()
        val counts = r.observePingCounts().first()
        assertThat(counts).containsExactly(older, 1, newer, 2)
        assertThat(counts[empty]).isNull()
    }

    @Test
    fun observeSessionsOverlapping_includesOpenAndCrossingSessionsOnly() = runTest {
        val r = repo()
        r.saveSession(TrackingSession(start = 0, end = 10, zoneId = "UTC", trigger = SessionTrigger.MANUAL)) // before
        val crossing = r.saveSession(TrackingSession(start = 5, end = 25, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        val open = r.saveSession(TrackingSession(start = 30, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        r.saveSession(TrackingSession(start = 50, end = 60, zoneId = "UTC", trigger = SessionTrigger.MANUAL)) // after

        assertThat(r.observeSessionsOverlapping(10, 40).first().map { it.id }).containsExactly(crossing, open).inOrder()
    }

    @Test
    fun saveOverride_updateReturnsRealId() = runTest {
        val r = repo()
        val id = r.saveOverride(
            ManualOverride(
                type = ManualOverrideType.START, start = 1, zoneId = "UTC",
                endMode = ManualOverrideEndMode.UNTIL_STOPPED, active = true,
            ),
        )
        assertThat(id).isGreaterThan(0L)
        val original = db.manualOverrideDao().getById(id)!!
        val updatedId = r.saveOverride(original.copy(active = false, end = 50))
        assertThat(updatedId).isEqualTo(id)
        val row = db.manualOverrideDao().getById(id)!!
        assertThat(row.active).isFalse()
        assertThat(row.end).isEqualTo(50)
    }
}
